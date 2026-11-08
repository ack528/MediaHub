package config

import (
	"reflect"
	"testing"
)

func TestDiffAndCopyScalar(t *testing.T) {
	a, b := Default(), Default()
	if d := Diff(a, b); len(d) != 0 {
		t.Fatalf("identical configs differ: %v", d)
	}
	b.Video.MaxTranscodes = 4
	b.Video.HWAccel = "none"
	b.Log.Level = "debug"
	b.Tunnel.Protocol = "quic"
	b.Roots = []Root{{Path: `D:\media`, Label: "D"}}
	b.Path, b.RootsUnset = "ignored", true // json:"-" 的字段不参与比较
	want := []string{"log.level", "roots", "tunnel.protocol", "video.hwaccel", "video.maxTranscodes"}
	if got := Diff(a, b); !reflect.DeepEqual(got, want) {
		t.Fatalf("Diff = %v, want %v", got, want)
	}
	if !CopyScalar(a, b, "video.maxTranscodes") || a.Video.MaxTranscodes != 4 {
		t.Fatalf("number should be copied in place, got %d", a.Video.MaxTranscodes)
	}
	if CopyScalar(a, b, "video.hwaccel") || a.Video.HWAccel != "qsv" {
		t.Fatal("strings must not be copied in place")
	}
	if CopyScalar(a, b, "no.such.key") {
		t.Fatal("unknown key")
	}
}
