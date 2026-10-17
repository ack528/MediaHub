//go:build !windows

package index

import (
	"context"
	"errors"
)

func watchRoot(ctx context.Context, root string, ev chan<- string) error {
	return errors.New("目录变更监视只在 Windows 上实现")
}
