// Package natsort 生成"自然排序键":数字按数值比较(2 < 10),忽略大小写。
// 键是普通字符串,可直接存进 SQLite 并用 BINARY 排序。
package natsort

import (
	"strings"
	"unicode"
)

const width = 20 // 数字段补零到 20 位(超长的数字段按长度前缀仍可保持大小关系)

func Key(s string) string {
	var b strings.Builder
	rs := []rune(strings.ToLower(s))
	for i := 0; i < len(rs); {
		if rs[i] >= '0' && rs[i] <= '9' {
			j := i
			for j < len(rs) && rs[j] >= '0' && rs[j] <= '9' {
				j++
			}
			digits := strings.TrimLeft(string(rs[i:j]), "0")
			if digits == "" {
				digits = "0"
			}
			if len(digits) > width {
				// 极端长数字:用 '~'(大于任何数字)+ 长度做前缀,保证"更长更大"
				b.WriteString("~")
				b.WriteString(pad(len(digits), 4))
				b.WriteString(digits)
			} else {
				b.WriteString(strings.Repeat("0", width-len(digits)))
				b.WriteString(digits)
			}
			i = j
			continue
		}
		r := rs[i]
		if unicode.IsSpace(r) {
			r = ' '
		}
		b.WriteRune(r)
		i++
	}
	return b.String()
}

func pad(n, w int) string {
	s := []byte("0000000000")[:w]
	for i := w - 1; i >= 0 && n > 0; i-- {
		s[i] = byte('0' + n%10)
		n /= 10
	}
	return string(s)
}
