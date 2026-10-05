// Package winfs 封装 Windows/NTFS 相关的文件系统调用:
// 带文件 ID 的批量目录枚举、卷信息、剩余空间、隐藏属性。
package winfs

import (
	"hash/fnv"
	"time"
)

// Entry 是一次目录枚举得到的条目。
type Entry struct {
	Name    string
	IsDir   bool
	Size    int64
	ModTime time.Time // 最后写入时间
	Created time.Time // 创建时间
	FileID  uint64    // NTFS 文件 ID;取不到时是路径哈希
	Hidden  bool
	System  bool
	Reparse bool // 符号链接/联接点:索引时一律跳过,避免环和越界
}

// Stable 由 (卷序列号, 文件 ID) 生成 63 位正整数,作为条目的稳定 ID。
// 重命名、移动不改变文件 ID,因而不改变条目 ID。
func Stable(volume uint32, fileID uint64) int64 {
	h := fnv.New64a()
	var b [12]byte
	b[0], b[1], b[2], b[3] = byte(volume), byte(volume>>8), byte(volume>>16), byte(volume>>24)
	for i := 0; i < 8; i++ {
		b[4+i] = byte(fileID >> (8 * i))
	}
	h.Write(b[:])
	return int64(h.Sum64() & 0x7fffffffffffffff)
}

// PathHash 在取不到文件 ID 的文件系统上作为回退。
func PathHash(p string) uint64 {
	h := fnv.New64a()
	h.Write([]byte(p))
	return h.Sum64()
}
