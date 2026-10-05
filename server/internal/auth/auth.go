// Package auth 提供本地账号与长期令牌(局域网单人场景)。
package auth

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

const iterations = 600_000

var ErrInvalid = errors.New("auth: invalid credentials")

type Service struct {
	DB        *sql.DB
	TokenDays int
	iter      int
}

// SetIterations 仅供测试降低 PBKDF2 迭代次数。
func (s *Service) SetIterations(n int) { s.iter = n }

func New(db *sql.DB, tokenDays int) *Service {
	if tokenDays <= 0 {
		tokenDays = 180
	}
	return &Service{DB: db, TokenDays: tokenDays, iter: iterations}
}

// HashPassword 返回 "pbkdf2-sha256$迭代次数$盐$哈希"。
func HashPassword(pw string, iter int) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	dk, err := pbkdf2.Key(sha256.New, pw, salt, iter, 32)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", iter, base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(dk)), nil
}

func VerifyPassword(stored, pw string) bool {
	parts := strings.Split(stored, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[1])
	if err != nil || iter < 1000 {
		return false
	}
	salt, err1 := base64.RawStdEncoding.DecodeString(parts[2])
	want, err2 := base64.RawStdEncoding.DecodeString(parts[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pw, salt, iter, len(want))
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(got, want) == 1
}

func (s *Service) CreateUser(name, pw string) error {
	name = strings.TrimSpace(name)
	if name == "" || len(pw) < 4 {
		return errors.New("用户名不能为空,密码至少 4 位")
	}
	h, err := HashPassword(pw, s.iter)
	if err != nil {
		return err
	}
	_, err = s.DB.Exec(`INSERT INTO users(name,pw_hash,created) VALUES(?,?,?)`, name, h, time.Now().Unix())
	if err != nil && strings.Contains(err.Error(), "UNIQUE") {
		return errors.New("用户已存在")
	}
	return err
}

func (s *Service) SetPassword(name, pw string) error {
	h, err := HashPassword(pw, s.iter)
	if err != nil {
		return err
	}
	res, err := s.DB.Exec(`UPDATE users SET pw_hash=? WHERE name=?`, h, name)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return errors.New("用户不存在")
	}
	return nil
}

func tokenHash(tok string) string {
	h := sha256.Sum256([]byte(tok))
	return hex.EncodeToString(h[:])
}

// Login 校验账号密码并签发令牌。令牌明文只返回这一次,库里只存哈希。
func (s *Service) Login(name, pw, device string) (token string, expires time.Time, userID int64, err error) {
	var id int64
	var stored string
	row := s.DB.QueryRow(`SELECT id, pw_hash FROM users WHERE name=?`, name)
	if e := row.Scan(&id, &stored); e != nil {
		// 对不存在的用户也做一次哈希计算,避免通过耗时判断用户是否存在
		_ = VerifyPassword("pbkdf2-sha256$1000$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", pw)
		return "", time.Time{}, 0, ErrInvalid
	}
	if !VerifyPassword(stored, pw) {
		return "", time.Time{}, 0, ErrInvalid
	}
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	now := time.Now()
	expires = now.AddDate(0, 0, s.TokenDays)
	_, err = s.DB.Exec(`INSERT INTO tokens(hash,user_id,device,expires,last_seen) VALUES(?,?,?,?,?)`,
		tokenHash(token), id, device, expires.Unix(), now.Unix())
	return token, expires, id, err
}

// Verify 校验令牌;使用中的令牌自动顺延有效期(滑动过期)。
func (s *Service) Verify(token string) (userID int64, ok bool) {
	if token == "" {
		return 0, false
	}
	h := tokenHash(token)
	var exp, seen int64
	if err := s.DB.QueryRow(`SELECT user_id, expires, last_seen FROM tokens WHERE hash=?`, h).Scan(&userID, &exp, &seen); err != nil {
		return 0, false
	}
	now := time.Now()
	if exp < now.Unix() {
		return 0, false
	}
	if now.Unix()-seen > 3600 {
		_, _ = s.DB.Exec(`UPDATE tokens SET last_seen=?, expires=? WHERE hash=?`, now.Unix(), now.AddDate(0, 0, s.TokenDays).Unix(), h)
	}
	return userID, true
}

func (s *Service) Logout(token string) {
	_, _ = s.DB.Exec(`DELETE FROM tokens WHERE hash=?`, tokenHash(token))
}

type UserInfo struct {
	Name    string `json:"name"`
	Created int64  `json:"created"`
}

func (s *Service) ListUsers() ([]UserInfo, error) {
	rows, err := s.DB.Query(`SELECT name, created FROM users ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []UserInfo{}
	for rows.Next() {
		var u UserInfo
		if err := rows.Scan(&u.Name, &u.Created); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// DeleteUser 删除账号及其所有令牌。
func (s *Service) DeleteUser(name string) error {
	var id int64
	if err := s.DB.QueryRow(`SELECT id FROM users WHERE name=?`, name).Scan(&id); err != nil {
		return errors.New("用户不存在")
	}
	if _, err := s.DB.Exec(`DELETE FROM tokens WHERE user_id=?`, id); err != nil {
		return err
	}
	_, err := s.DB.Exec(`DELETE FROM users WHERE id=?`, id)
	return err
}
