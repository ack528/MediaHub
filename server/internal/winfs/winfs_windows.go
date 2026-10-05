//go:build windows

package winfs

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	attrHidden  = 0x2
	attrSystem  = 0x4
	attrDir     = 0x10
	attrReparse = 0x400
)

// 偏移见 FILE_ID_BOTH_DIR_INFO(x64)。
const (
	offNext     = 0
	offCreate   = 8
	offWrite    = 24
	offEOF      = 40
	offAttr     = 56
	offNameLen  = 60
	offFileID   = 96
	offFileName = 104
)

const extPrefix = `\\?\`

// 这些名字在 Win32 里是设备(CON、NUL、COM1……),带不带扩展名都不能当普通文件名用。
var reserved = map[string]bool{"CON": true, "PRN": true, "AUX": true, "NUL": true}

func isReserved(comp string) bool {
	base := comp
	if i := strings.IndexByte(base, '.'); i >= 0 {
		base = base[:i]
	}
	base = strings.ToUpper(strings.TrimRight(base, " "))
	if reserved[base] {
		return true
	}
	return len(base) == 4 && (strings.HasPrefix(base, "COM") || strings.HasPrefix(base, "LPT")) && base[3] >= '1' && base[3] <= '9'
}

// NeedExt 判断路径是否必须用扩展路径(\\?\ 前缀)才能访问:超长,或某一级名称以空格 / 点结尾、是 Win32 保留设备名。
// 普通 Win32 路径会把这些名称"规范化"掉(去掉结尾空格 / 点),于是找不到目录或报 123"文件名、目录名或卷标语法不正确"。
func NeedExt(p string) bool {
	if strings.HasPrefix(p, extPrefix) {
		return false
	}
	if len(p) >= 240 {
		return true
	}
	for _, c := range strings.Split(p, `\`) {
		if c == "" || c == "." || c == ".." || (len(c) == 2 && c[1] == ':') {
			continue
		}
		if strings.HasSuffix(c, " ") || strings.HasSuffix(c, ".") || isReserved(c) {
			return true
		}
	}
	return false
}

// ExtPath 返回扩展路径形式(\\?\D:\x 或 \\?\UNC\server\share\x);不是绝对路径时原样返回。
func ExtPath(p string) string {
	switch {
	case strings.HasPrefix(p, extPrefix):
		return p
	case strings.HasPrefix(p, `\\`):
		return extPrefix + `UNC\` + p[2:]
	case len(p) >= 3 && p[1] == ':' && (p[2] == '\\' || p[2] == '/'):
		return extPrefix + strings.ReplaceAll(p, "/", `\`)
	}
	return p
}

// Path 给文件系统调用用的路径:正常路径原样返回,只有必须时才换成扩展路径。
func Path(p string) string {
	if NeedExt(p) {
		return ExtPath(p)
	}
	return p
}

// retryable:换成扩展路径重试有可能成功的错误(找不到 / 路径不存在 / 名称语法不正确 / 路径名不合法)。
func retryable(err error) bool {
	var en syscall.Errno
	if !errors.As(err, &en) {
		return false
	}
	switch en {
	case 2, 3, 123, 161:
		return true
	}
	return false
}

// Open 打开文件;普通路径失败且错误可能是"名称被规范化"引起时,用扩展路径再试一次。
func Open(p string) (*os.File, error) {
	f, err := os.Open(Path(p))
	if err != nil && retryable(err) {
		if e := ExtPath(p); e != p && e != Path(p) {
			if f2, err2 := os.Open(e); err2 == nil {
				return f2, nil
			}
		}
	}
	return f, err
}

// Stat 同 Open:必要时用扩展路径。
func Stat(p string) (os.FileInfo, error) {
	st, err := os.Stat(Path(p))
	if err != nil && retryable(err) {
		if e := ExtPath(p); e != p && e != Path(p) {
			if st2, err2 := os.Stat(e); err2 == nil {
				return st2, nil
			}
		}
	}
	return st, err
}

func ft(b []byte) time.Time {
	v := *(*int64)(unsafe.Pointer(&b[0]))
	if v == 0 {
		return time.Time{}
	}
	return time.Unix(0, (v-116444736000000000)*100)
}

// ListDir 一次系统调用批量返回目录项及其 NTFS 文件 ID。
// 取不到(非 NTFS 等)时回退到 os.ReadDir,文件 ID 用路径哈希代替。
func ListDir(dir string) ([]Entry, error) {
	out, err := listDir(Path(dir))
	if err != nil && retryable(err) {
		if e := ExtPath(dir); e != dir && e != Path(dir) {
			if out2, err2 := listDir(e); err2 == nil {
				return out2, nil
			}
		}
	}
	return out, err
}

func listDir(dir string) ([]Entry, error) {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return nil, err
	}
	h, err := windows.CreateFile(p, windows.FILE_LIST_DIRECTORY|windows.SYNCHRONIZE,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, nil,
		windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		return nil, err
	}
	defer windows.CloseHandle(h)

	buf := make([]byte, 256*1024)
	var out []Entry
	class := uint32(windows.FileIdBothDirectoryRestartInfo)
	for {
		err := windows.GetFileInformationByHandleEx(h, class, &buf[0], uint32(len(buf)))
		class = uint32(windows.FileIdBothDirectoryInfo)
		if err != nil {
			if errors.Is(err, windows.ERROR_NO_MORE_FILES) {
				return out, nil
			}
			if len(out) == 0 {
				return listFallback(dir)
			}
			return out, err
		}
		off := 0
		for {
			rec := buf[off:]
			nameLen := int(*(*uint32)(unsafe.Pointer(&rec[offNameLen])))
			u := make([]uint16, nameLen/2)
			for i := range u {
				u[i] = *(*uint16)(unsafe.Pointer(&rec[offFileName+2*i]))
			}
			name := string(utf16.Decode(u))
			if name != "." && name != ".." {
				attr := *(*uint32)(unsafe.Pointer(&rec[offAttr]))
				out = append(out, Entry{
					Name:    name,
					IsDir:   attr&attrDir != 0,
					Size:    *(*int64)(unsafe.Pointer(&rec[offEOF])),
					ModTime: ft(rec[offWrite:]),
					Created: ft(rec[offCreate:]),
					FileID:  *(*uint64)(unsafe.Pointer(&rec[offFileID])),
					Hidden:  attr&attrHidden != 0,
					System:  attr&attrSystem != 0,
					Reparse: attr&attrReparse != 0,
				})
			}
			next := int(*(*uint32)(unsafe.Pointer(&rec[offNext])))
			if next == 0 {
				break
			}
			off += next
		}
	}
}

func listFallback(dir string) ([]Entry, error) {
	des, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	out := make([]Entry, 0, len(des))
	for _, de := range des {
		info, err := de.Info()
		if err != nil {
			continue
		}
		out = append(out, Entry{
			Name:    de.Name(),
			IsDir:   de.IsDir(),
			Size:    info.Size(),
			ModTime: info.ModTime(),
			Created: info.ModTime(),
			FileID:  PathHash(filepath.Join(dir, de.Name())),
			Reparse: info.Mode()&os.ModeSymlink != 0,
		})
	}
	return out, nil
}

// VolumeInfo 返回根目录所在卷的序列号与卷 GUID 路径。
func VolumeInfo(root string) (serial uint32, guid string, err error) {
	vol := filepath.VolumeName(root) + `\`
	rp, err := windows.UTF16PtrFromString(vol)
	if err != nil {
		return 0, "", err
	}
	var s uint32
	if err = windows.GetVolumeInformation(rp, nil, 0, &s, nil, nil, nil, 0); err != nil {
		return 0, "", err
	}
	var gbuf [64]uint16
	if e := windows.GetVolumeNameForVolumeMountPoint(rp, &gbuf[0], uint32(len(gbuf))); e == nil {
		guid = windows.UTF16ToString(gbuf[:])
	}
	return s, guid, nil
}

// FreeBytes 返回路径所在卷的可用字节数。
func FreeBytes(path string) (int64, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	var free, total, totalFree uint64
	if err := windows.GetDiskFreeSpaceEx(p, &free, &total, &totalFree); err != nil {
		return 0, err
	}
	return int64(free), nil
}

// FileID 返回单个文件/目录的 NTFS 文件 ID(用于单文件校验)。
func FileID(path string) (uint64, error) {
	id, err := fileID(Path(path))
	if err != nil && retryable(err) {
		if e := ExtPath(path); e != path && e != Path(path) {
			if id2, err2 := fileID(e); err2 == nil {
				return id2, nil
			}
		}
	}
	return id, err
}

func fileID(path string) (uint64, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	h, err := windows.CreateFile(p, 0, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, nil,
		windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		return 0, err
	}
	defer windows.CloseHandle(h)
	var fi windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(h, &fi); err != nil {
		return 0, err
	}
	return uint64(fi.FileIndexHigh)<<32 | uint64(fi.FileIndexLow), nil
}

// SetHidden 给文件/目录加上隐藏属性(缓存目录用)。
func SetHidden(path string) error {
	p, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	return syscall.SetFileAttributes(p, syscall.FILE_ATTRIBUTE_HIDDEN|syscall.FILE_ATTRIBUTE_DIRECTORY)
}
