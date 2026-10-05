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

func longPath(p string) string {
	if strings.HasPrefix(p, `\?\`) || len(p) < 240 {
		return p
	}
	if strings.HasPrefix(p, `\`) {
		return `\?\UNC\` + p[2:]
	}
	return `\?\` + p
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
	p, err := windows.UTF16PtrFromString(longPath(dir))
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
	p, err := windows.UTF16PtrFromString(longPath(path))
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
