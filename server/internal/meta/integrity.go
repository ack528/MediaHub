package meta

import (
	"encoding/binary"
	"mediahub/internal/winfs"
	"strings"
)

// 下载中断留下的 MP4 / MOV 文件是最常见的"打不开"原因:文件头(moov)里记录的是完整视频,
// 但文件实际被截断了(或者 moov 在文件末尾,根本没下载到)。手机播放时会请求被截断位置之后的数据,
// 服务端只能回 416。这里用 ISO BMFF 的顶层盒子结构低成本地判断(只读几个盒子头,不读视频数据)。

var isoBMFFExts = map[string]bool{"mp4": true, "m4v": true, "mov": true, "3gp": true, "3g2": true}

// IsISOBMFF 该扩展名是否可以用 MP4Incomplete 检查。
func IsISOBMFF(ext string) bool { return isoBMFFExts[strings.ToLower(ext)] }

// MP4Incomplete 判断 MP4 / MOV 是否不完整:顶层某个盒子声明的大小超出了文件末尾,或者整个文件里没有 moov。
func MP4Incomplete(path string) (bool, error) {
	f, err := winfs.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return false, err
	}
	size := st.Size()
	if size < 16 {
		return true, nil
	}
	var off int64
	var hasMoov, hasFtyp bool
	hdr := make([]byte, 16)
	for off+8 <= size {
		if _, err := f.ReadAt(hdr[:8], off); err != nil {
			return true, nil
		}
		boxSize := int64(binary.BigEndian.Uint32(hdr[0:4]))
		typ := string(hdr[4:8])
		hlen := int64(8)
		switch boxSize {
		case 0: // 盒子一直延伸到文件末尾(合法,常见于最后的 mdat)
			boxSize = size - off
		case 1: // 64 位大小
			if _, err := f.ReadAt(hdr[8:16], off+8); err != nil {
				return true, nil
			}
			boxSize = int64(binary.BigEndian.Uint64(hdr[8:16]))
			hlen = 16
		}
		if boxSize < hlen {
			return true, nil // 盒子头本身不合法:文件损坏
		}
		switch typ {
		case "moov":
			hasMoov = true
		case "ftyp":
			hasFtyp = true
		}
		if off+boxSize > size {
			return true, nil // 声明的大小超出文件末尾 = 被截断
		}
		off += boxSize
	}
	return !hasMoov || !hasFtyp && off == 0, nil
}
