// Package tlsx 为局域网服务生成并管理自签名证书。
//
// 单用户局域网场景不适合公共 CA(没有域名,IP 会变):服务端第一次启动时生成一张 ECDSA P-256 自签名证书
// (有效期 10 年)存在数据目录里;手机第一次连接时显示证书指纹,用户与管理程序里显示的指纹核对一致后"信任",
// 以后手机只接受这张证书(证书固定),所以不依赖域名 / IP,换 IP 也不影响。
// 传输使用 TLS 1.3(AES-GCM / ChaCha20-Poly1305,Go 会按手机是否有 AES 硬件加速自动选择)+ HTTP/2,握手快、吞吐高。
package tlsx

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	certName = "tls-cert.pem"
	keyName  = "tls-key.pem"
	validFor = 10 * 365 * 24 * time.Hour
	renewMin = 30 * 24 * time.Hour // 离过期不足 30 天就重新生成
)

// Fingerprint 返回证书(DER)的 SHA-256 指纹,形如 "AB:CD:…"。
func Fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = fmt.Sprintf("%02X", b)
	}
	return strings.Join(parts, ":")
}

// Ensure 保证数据目录里有可用的证书与私钥(没有 / 损坏 / 快过期就重新生成),返回文件路径与指纹。
func Ensure(dataDir string) (certFile, keyFile, fingerprint string, err error) {
	certFile, keyFile = filepath.Join(dataDir, certName), filepath.Join(dataDir, keyName)
	if der, ok := loadValid(certFile, keyFile); ok {
		return certFile, keyFile, Fingerprint(der), nil
	}
	der, keyPEM, err := generate()
	if err != nil {
		return "", "", "", err
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return "", "", "", err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	if err := os.WriteFile(keyFile, keyPEM, 0o600); err != nil {
		return "", "", "", err
	}
	if err := os.WriteFile(certFile, certPEM, 0o644); err != nil {
		return "", "", "", err
	}
	return certFile, keyFile, Fingerprint(der), nil
}

func loadValid(certFile, keyFile string) ([]byte, bool) {
	cp, err := os.ReadFile(certFile)
	if err != nil {
		return nil, false
	}
	if _, err := os.Stat(keyFile); err != nil {
		return nil, false
	}
	blk, _ := pem.Decode(cp)
	if blk == nil || blk.Type != "CERTIFICATE" {
		return nil, false
	}
	c, err := x509.ParseCertificate(blk.Bytes)
	if err != nil || time.Until(c.NotAfter) < renewMin {
		return nil, false
	}
	return blk.Bytes, true
}

func generate() (der []byte, keyPEM []byte, err error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	host, _ := os.Hostname()
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "MediaHub", Organization: []string{"MediaHub"}},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(validFor),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	if host != "" {
		tpl.DNSNames = append(tpl.DNSNames, host)
	}
	if addrs, e := net.InterfaceAddrs(); e == nil {
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && !ipn.IP.IsLoopback() && !ipn.IP.IsLinkLocalUnicast() {
				tpl.IPAddresses = append(tpl.IPAddresses, ipn.IP)
			}
		}
	}
	der, err = x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	kb, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, nil, err
	}
	return der, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: kb}), nil
}
