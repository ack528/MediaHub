package winfs

import (
	"errors"
	"syscall"
)

// ErrKind 把目录读取错误归类,给出中文说明,方便日志和管理程序告诉用户"到底是什么问题"。
// kind:
//
//	denied  没有权限 / 被占用(系统保护目录、别的程序独占),属于正常现象,跳过即可
//	gone    目录在枚举过程中被移走或删除
//	offline 磁盘 / 网络位置暂时不可用(掉线、休眠没醒、网络共享断开)
//	io      磁盘读取出错(坏道、数据线或硬盘故障)
//	name    名称不符合 Windows 规则(结尾的空格 / 点、保留字符等,多半是别的系统创建的),已尝试扩展路径仍无法读取
//	other   其它
func ErrKind(err error) (kind string, code int, text string) {
	var en syscall.Errno
	if !errors.As(err, &en) {
		return "other", 0, err.Error()
	}
	code = int(en)
	switch code {
	case 5, 32, 33: // ACCESS_DENIED / SHARING_VIOLATION / LOCK_VIOLATION
		return "denied", code, "没有权限访问(系统保护目录,或被别的程序占用)"
	case 123, 161, 206: // INVALID_NAME / BAD_PATHNAME / FILENAME_EXCED_RANGE
		return "name", code, "名称不符合 Windows 规则(如结尾带空格或点、含 ? * : 等字符,多半是 Linux / Mac 创建的),已尝试扩展路径仍无法读取;改一下文件夹名即可"
	case 2, 3: // FILE_NOT_FOUND / PATH_NOT_FOUND
		return "gone", code, "目录已被移走或删除"
	case 21, 53, 59, 64, 67, 1219, 1231: // NOT_READY / BAD_NETPATH / UNEXP_NET_ERR / NETNAME_DELETED / BAD_NET_NAME ...
		return "offline", code, "磁盘或网络位置暂时不可用(掉线或休眠未唤醒)"
	case 23, 27, 1117, 1392, 1393: // CRC / SECTOR_NOT_FOUND / IO_DEVICE / FILE_CORRUPT / DISK_CORRUPT
		return "io", code, "磁盘读取出错(可能有坏道,或硬盘 / 数据线有问题)"
	}
	return "other", code, err.Error()
}
