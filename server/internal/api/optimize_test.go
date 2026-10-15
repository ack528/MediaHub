package api

import (
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func jsonUnmarshal(b []byte, v any) error { return json.Unmarshal(b, v) }

// 按归一化坐标画图:同一个 pattern 用不同分辨率画出来,内容"看起来一样"。
func writePattern(t *testing.T, path string, w, h int, kind int, q int) {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			u, v := float64(x)/float64(w), float64(y)/float64(h)
			var r, g, b float64
			if kind == 0 {
				r = 255 * u
				g = 255 * v
				b = 128 + 127*math.Sin(6*u*v*math.Pi)
				if (u-0.5)*(u-0.5)+(v-0.4)*(v-0.4) < 0.04 {
					r, g, b = 240, 30, 30
				}
			} else {
				r = 255 * (1 - v)
				g = 128 + 127*math.Cos(9*u*math.Pi)
				b = 255 * u * (1 - u) * 4
				if math.Abs(u-v) < 0.1 {
					r, g, b = 20, 200, 60
				}
			}
			img.Set(x, y, color.RGBA{uint8(r), uint8(g), uint8(b), 255})
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: q}); err != nil {
		t.Fatal(err)
	}
}

func adminHdr(e *env) map[string]string {
	e.srv.AdminKey = "k-opt-test"
	return map[string]string{"X-Admin-Key": "k-opt-test"}
}

func rescanAll(t *testing.T, e *env) {
	roots, _ := e.srv.Idx.SyncRoots()
	if err := e.srv.Idx.ScanRoot(context.Background(), roots[0]); err != nil {
		t.Fatal(err)
	}
	if err := e.srv.Idx.Enrich(context.Background(), roots[0]); err != nil {
		t.Fatal(err)
	}
}

func idByName(t *testing.T, e *env, name string) int64 {
	var id int64
	if err := e.srv.DB.QueryRow(`SELECT id FROM media WHERE name=?`, name).Scan(&id); err != nil {
		t.Fatalf("%s 不在索引里: %v", name, err)
	}
	return id
}

func TestDedupeAndTrash(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	dir := filepath.Join(e.lib, "去重")
	os.MkdirAll(dir, 0o755)
	writePattern(t, filepath.Join(dir, "a1.jpg"), 256, 256, 0, 90)
	b, _ := os.ReadFile(filepath.Join(dir, "a1.jpg"))
	os.WriteFile(filepath.Join(dir, "a1_copy.jpg"), b, 0o644)            // 完全相同
	writePattern(t, filepath.Join(dir, "a1_small.jpg"), 128, 128, 0, 60) // 缩小 + 重新压缩:相似
	writePattern(t, filepath.Join(dir, "b.jpg"), 256, 256, 1, 90)        // 完全不同
	clip := filepath.Join(e.lib, "相册", "嵌套", "更深", "clip.mp4")
	vb, _ := os.ReadFile(clip)
	os.WriteFile(filepath.Join(dir, "clip_copy.mp4"), vb, 0o644) // 视频完全相同
	// 同一段视频低码率重新编码:相似视频(样本很短,拼长一点让取帧位置有意义)
	long := filepath.Join(dir, "clip_long.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-stream_loop", "5", "-i", clip, "-c", "copy", long).CombinedOutput(); err != nil {
		t.Skipf("无法生成长样本: %v %s", err, out)
	}
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-i", long, "-c:v", "libx264", "-b:v", "150k", "-an", filepath.Join(dir, "clip_long_lo.mp4")).CombinedOutput(); err != nil {
		t.Skipf("无法重新编码: %v %s", err, out)
	}
	rescanAll(t, e)

	if resp, _ := e.do("POST", "/api/v1/admin/dedupe/scan", map[string]any{"threshold": 6}, hdr); resp.StatusCode != 202 {
		t.Fatalf("scan = %d", resp.StatusCode)
	}
	var st dedupeStatus
	deadline := time.Now().Add(120 * time.Second)
	for time.Now().Before(deadline) {
		_, b := e.do("GET", "/api/v1/admin/dedupe/status", nil, hdr)
		if err := jsonUnmarshal(b, &st); err != nil {
			t.Fatal(err)
		}
		if st.State != "running" {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if st.State != "done" {
		t.Fatalf("检测没有完成: %+v", st)
	}
	type gresp struct {
		Total  int
		Groups []dedupeGroup
	}
	get := func(kind string) gresp {
		var g gresp
		_, b := e.do("GET", "/api/v1/admin/dedupe/groups?kind="+kind, nil, hdr)
		if err := jsonUnmarshal(b, &g); err != nil {
			t.Fatal(err)
		}
		return g
	}
	names := func(g dedupeGroup) string {
		var n []string
		for _, it := range g.Items {
			n = append(n, it.Name)
		}
		return strings.Join(n, ",")
	}
	has := func(gs []dedupeGroup, want ...string) bool {
		for _, g := range gs {
			s := names(g)
			ok := true
			for _, w := range want {
				if !strings.Contains(s, w) {
					ok = false
				}
			}
			if ok {
				return true
			}
		}
		return false
	}
	ex := get("exact")
	if !has(ex.Groups, "a1.jpg", "a1_copy.jpg") {
		t.Fatalf("exact 应包含 a1 与它的副本: %+v", ex)
	}
	if !has(ex.Groups, "clip.mp4", "clip_copy.mp4") {
		t.Fatalf("exact 应包含两个相同的视频: %+v", ex)
	}
	for _, g := range ex.Groups {
		if strings.Contains(names(g), "b.jpg") {
			t.Fatal("b.jpg 不应该在重复组里")
		}
		if !g.Items[0].Keep || g.Items[1].Keep {
			t.Fatal("每组第一个是推荐保留的,其余不是")
		}
	}
	im := get("image")
	if !has(im.Groups, "a1.jpg", "a1_small.jpg") {
		t.Fatalf("image 应包含 a1 与缩小重压缩的版本: %+v (status %+v)", im, st)
	}
	for _, g := range im.Groups {
		if strings.Contains(names(g), "b.jpg") {
			t.Fatalf("b.jpg 不应该和 a1 相似: %s", names(g))
		}
	}
	vd := get("video")
	if !has(vd.Groups, "clip_long.mp4", "clip_long_lo.mp4") {
		t.Fatalf("video 应包含重新编码的相似视频: %+v", vd)
	}

	// 整组都选中:被拒绝
	var all []int64
	for _, it := range ex.Groups[0].Items {
		all = append(all, it.ID)
	}
	if resp, _ := e.do("POST", "/api/v1/admin/dedupe/trash", map[string]any{"ids": all}, hdr); resp.StatusCode != 400 {
		t.Fatalf("整组删除应该被拒绝,实际 %d", resp.StatusCode)
	}
	// 删除一个副本:移进回收区,原位置没有了
	copyID := idByName(t, e, "a1_copy.jpg")
	var tr struct {
		Moved int
		Freed int64
		Batch string
	}
	_, b2 := e.do("POST", "/api/v1/admin/dedupe/trash", map[string]any{"ids": []int64{copyID}}, hdr)
	if err := jsonUnmarshal(b2, &tr); err != nil || tr.Moved != 1 {
		t.Fatalf("trash: %s", b2)
	}
	if _, err := os.Stat(filepath.Join(dir, "a1_copy.jpg")); err == nil {
		t.Fatal("副本应该已经移走")
	}
	inTrash := filepath.Join(e.lib, ".mediahub", "trash", tr.Batch, "去重", "a1_copy.jpg")
	if _, err := os.Stat(inTrash); err != nil {
		t.Fatalf("回收区里应该有文件: %v", err)
	}
	// 回收区列表 + 还原
	var lst struct {
		Batches []struct {
			Batch  string
			Files  int
			RootID int64
		}
	}
	_, b3 := e.do("GET", "/api/v1/admin/trash", nil, hdr)
	_ = jsonUnmarshal(b3, &lst)
	if len(lst.Batches) != 1 || lst.Batches[0].Files != 1 {
		t.Fatalf("trash list: %s", b3)
	}
	_, b4 := e.do("POST", "/api/v1/admin/trash/restore", map[string]any{"rootId": lst.Batches[0].RootID, "batches": []string{tr.Batch}}, hdr)
	if !strings.Contains(string(b4), `"restored":1`) {
		t.Fatalf("restore: %s", b4)
	}
	if _, err := os.Stat(filepath.Join(dir, "a1_copy.jpg")); err != nil {
		t.Fatal("还原后原位置应该有文件")
	}
	// 清空:再删一次然后 purge
	rescanAll(t, e)
	copyID = idByName(t, e, "a1_copy.jpg")
	_, b2 = e.do("POST", "/api/v1/admin/dedupe/trash", map[string]any{"ids": []int64{copyID}}, hdr)
	_ = jsonUnmarshal(b2, &tr)
	inTrash = filepath.Join(e.lib, ".mediahub", "trash", tr.Batch, "去重", "a1_copy.jpg")
	_, b5 := e.do("POST", "/api/v1/admin/trash/purge", map[string]any{"rootId": lst.Batches[0].RootID, "batches": []string{tr.Batch}}, hdr)
	if !strings.Contains(string(b5), `"purged":1`) {
		t.Fatalf("purge: %s", b5)
	}
	if _, err := os.Stat(inTrash); err == nil {
		t.Fatal("清空后回收区里不应该还有文件")
	}
	// 路径穿越:batch 里带 .. 被忽略
	_, b6 := e.do("POST", "/api/v1/admin/trash/purge", map[string]any{"rootId": lst.Batches[0].RootID, "batches": []string{"..", `..\..`, "a/b"}}, hdr)
	if !strings.Contains(string(b6), `"purged":0`) {
		t.Fatalf("非法批次名必须被忽略: %s", b6)
	}
	// 没有管理密钥:拒绝
	if resp, _ := e.do("GET", "/api/v1/admin/dedupe/status", nil, nil); resp.StatusCode != 403 && resp.StatusCode != 401 {
		t.Fatalf("没有管理密钥应该被拒绝,实际 %d", resp.StatusCode)
	}
}

func TestCompressPipeline(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	e.srv.Cfg.Video.HWAccel = "none" // 测试机没有 QSV:走软件编码
	dir := filepath.Join(e.lib, "压缩")
	os.MkdirAll(dir, 0o755)
	big := filepath.Join(dir, "big.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30", "-f", "lavfi", "-i", "sine=f=440:d=6",
		"-t", "6", "-c:v", "libx264", "-b:v", "6M", "-minrate", "6M", "-maxrate", "6M", "-bufsize", "6M", "-c:a", "aac", big).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "big.mp4")
	var origSize int64
	_ = e.srv.DB.QueryRow(`SELECT size FROM media WHERE id=?`, id).Scan(&origSize)

	// 预估(dryRun)不入队
	var plan compressPlan
	_, b := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800, "preset": "veryfast", "dryRun": true}, hdr)
	if err := jsonUnmarshal(b, &plan); err != nil || plan.Count != 1 || plan.Est <= 0 || plan.Est >= origSize {
		t.Fatalf("dryRun: %s", b)
	}
	var n int
	_ = e.srv.DB.QueryRow(`SELECT COUNT(*) FROM opt_job`).Scan(&n)
	if n != 0 {
		t.Fatal("dryRun 不应该入队")
	}
	// 码率不合理被拒绝
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "mode": "abs", "value": 50}, hdr); resp.StatusCode != 400 {
		t.Fatalf("过低的码率应该 400,实际 %d", resp.StatusCode)
	}
	// 入队并启动 worker
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e.srv.StartOptimizeWorker(ctx)
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800, "preset": "veryfast"}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	// 重复入队被跳过
	_, b = e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800}, hdr)
	if err := jsonUnmarshal(b, &plan); err != nil || plan.Count != 0 {
		t.Fatalf("已经在队列里的不应再入队: %s", b)
	}
	var jobID int64
	var job optJob
	deadline := time.Now().Add(180 * time.Second)
	for time.Now().Before(deadline) {
		var jl struct{ Jobs []optJob }
		_, b := e.do("GET", "/api/v1/admin/compress/jobs", nil, hdr)
		_ = jsonUnmarshal(b, &jl)
		if len(jl.Jobs) > 0 {
			job = jl.Jobs[0]
			jobID = job.ID
			if job.State == "done" || job.State == "failed" {
				break
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	if job.State != "done" {
		t.Fatalf("压缩没有完成: %+v", job)
	}
	if job.New <= 0 || job.New >= origSize/2 {
		t.Fatalf("压缩后应该明显变小: %d → %d", origSize, job.New)
	}
	// 原文件还没动
	if st, err := os.Stat(big); err != nil || st.Size() != origSize {
		t.Fatal("替换之前原文件不能变")
	}
	// 替换:原文件进回收区,原位置是 hevc 的压缩版
	var ap struct {
		Applied int
		Saved   int64
		Failed  []string
		Batch   string
	}
	_, b = e.do("POST", "/api/v1/admin/compress/apply", map[string]any{"ids": []int64{jobID}}, hdr)
	if err := jsonUnmarshal(b, &ap); err != nil || ap.Applied != 1 {
		t.Fatalf("apply: %s", b)
	}
	st, err := os.Stat(big)
	if err != nil || st.Size() != job.New {
		t.Fatalf("原位置应该是压缩版: %v", err)
	}
	out, _ := exec.Command(e.srv.Cfg.Tools.FFprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", big).Output()
	if f := strings.Fields(string(out)); len(f) == 0 || f[0] != "hevc" {
		t.Fatalf("压缩版应该是 hevc: %q", out)
	}
	orig := filepath.Join(e.lib, ".mediahub", "trash", ap.Batch, "压缩", "big.mp4")
	if st, err := os.Stat(orig); err != nil || st.Size() != origSize {
		t.Fatalf("原文件应该在回收区里: %v", err)
	}
	// 输出目录里不留残渣
	if ents, _ := os.ReadDir(filepath.Join(e.lib, ".mediahub", "optimize")); len(ents) != 0 {
		t.Fatalf("optimize 目录应该已空: %v", ents)
	}
}
