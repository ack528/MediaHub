package config

import (
	"reflect"
	"sort"
	"strings"
)

// 热更新用:比较两份配置哪些设置变了,以及把"数字 / 开关"类的设置从新配置复制到正在使用的配置里。

// Diff 返回两份配置里不同的设置,用点分路径表示(和 config.json 里的 JSON 路径一致,例如 "video.maxTranscodes");
// 数组 / 字符串 / 数字等"叶子"整体比较(roots、exclude 这类数组算一项)。
func Diff(a, b *Config) []string {
	var out []string
	walk(reflect.ValueOf(a).Elem(), reflect.ValueOf(b).Elem(), "", func(path string, x, y reflect.Value) {
		if !reflect.DeepEqual(x.Interface(), y.Interface()) {
			out = append(out, path)
		}
	})
	sort.Strings(out)
	return out
}

// CopyScalar 把 src 里 key 对应的设置复制到 dst;只处理开关和数字(可以直接原地改的),其余返回 false。
func CopyScalar(dst, src *Config, key string) bool {
	done := false
	walk(reflect.ValueOf(dst).Elem(), reflect.ValueOf(src).Elem(), "", func(path string, d, s reflect.Value) {
		if path != key || !d.CanSet() {
			return
		}
		switch d.Kind() {
		case reflect.Bool, reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
			reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Float32, reflect.Float64:
			d.Set(s)
			done = true
		}
	})
	return done
}

// walk 同时遍历两个相同类型的结构,对每个叶子调用 fn(叶子 = 非结构体字段;带 json:"-" 的字段跳过)。
func walk(a, b reflect.Value, prefix string, fn func(path string, a, b reflect.Value)) {
	t := a.Type()
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		tag := strings.Split(f.Tag.Get("json"), ",")[0]
		if tag == "-" || !f.IsExported() {
			continue
		}
		if tag == "" {
			tag = f.Name
		}
		path := tag
		if prefix != "" {
			path = prefix + "." + tag
		}
		if f.Type.Kind() == reflect.Struct {
			walk(a.Field(i), b.Field(i), path, fn)
			continue
		}
		fn(path, a.Field(i), b.Field(i))
	}
}
