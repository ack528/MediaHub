//go:build windows

package index

import (
	"context"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

// watchRoot 用 ReadDirectoryChangesW 监视整个根目录(含子目录)。每收到一条变更就向 ev 发一个"受影响的目录"
// (文件变化 → 它所在的目录;目录新建 / 改名 / 删除 → 它自己和它的父目录)。缓冲区溢出(一下子变化太多)时发一个空串,
// 调用方据此改做整盘扫描。ctx 结束或目录消失时返回。
func watchRoot(ctx context.Context, root string, ev chan<- string) error {
	p, err := windows.UTF16PtrFromString(root)
	if err != nil {
		return err
	}
	h, err := windows.CreateFile(p, windows.FILE_LIST_DIRECTORY,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, nil,
		windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(h)
	go func() { // 结束时关掉句柄,正在阻塞的 ReadDirectoryChanges 会立刻返回
		<-ctx.Done()
		_ = windows.CancelIoEx(h, nil)
	}()
	buf := make([]byte, 128*1024)
	const mask = windows.FILE_NOTIFY_CHANGE_FILE_NAME | windows.FILE_NOTIFY_CHANGE_DIR_NAME |
		windows.FILE_NOTIFY_CHANGE_LAST_WRITE | windows.FILE_NOTIFY_CHANGE_SIZE
	send := func(s string) bool {
		select {
		case ev <- s:
			return true
		case <-ctx.Done():
			return false
		}
	}
	for {
		var n uint32
		if err := windows.ReadDirectoryChanges(h, &buf[0], uint32(len(buf)), true, mask, &n, nil, 0); err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		if n == 0 { // 缓冲区溢出:丢了一些通知
			if !send("") {
				return nil
			}
			continue
		}
		off := uint32(0)
		for off < n {
			fni := (*windows.FileNotifyInformation)(unsafe.Pointer(&buf[off]))
			nameLen := fni.FileNameLength / 2
			name := windows.UTF16ToString(unsafe.Slice(&fni.FileName, nameLen))
			full := filepath.Join(root, name)
			// 文件变化 → 所在目录;目录本身新建 / 改名 / 删除 → 它自己(扫描时判断存在与否)和父目录。统一都发:多发的无害,扫描会去重
			if !send(filepath.Dir(full)) || !send(full) {
				return nil
			}
			if fni.NextEntryOffset == 0 {
				break
			}
			off += fni.NextEntryOffset
		}
	}
}
