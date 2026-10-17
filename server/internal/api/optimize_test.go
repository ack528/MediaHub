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
	e.srv.Cfg.Optimize.AutoReplace = false // 这个测试走手动替换;自动替换见 TestCompressAutoReplace
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

// 一键清除队列:排队 / 失败 / 已取消的全部删除;「已完成待替换」默认保留,includeDone 时连压缩版文件一起清掉;已替换(applied)的历史记录永远不动。
func TestCompressClear(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	dir := filepath.Join(e.lib, "队列")
	os.MkdirAll(dir, 0o755)
	big := filepath.Join(dir, "q.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30", "-t", "4", "-c:v", "libx264", "-b:v", "6M", "-minrate", "6M", "-maxrate", "6M", "-bufsize", "6M", big).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "q.mp4")
	// 没启动 worker:入队的任务一直是 queued
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	out := filepath.Join(e.lib, ".mediahub", "optimize", "x.mkv")
	os.MkdirAll(filepath.Dir(out), 0o755)
	os.WriteFile(out, []byte("fake"), 0o644)
	now := time.Now().Unix()
	for _, st := range []string{"failed", "cancelled", "done", "applied"} {
		e.srv.DB.Exec(`INSERT INTO opt_job(media_id, state, codec, bitrate_kbps, preset, out_path, created, updated) VALUES(?,?,?,?,?,?,?,?)`, id+1000, st, "h265", 800, "medium", map[bool]string{true: out, false: ""}[st == "done"], now, now)
	}
	count := func(st string) int {
		var n int
		_ = e.srv.DB.QueryRow(`SELECT COUNT(*) FROM opt_job WHERE state=?`, st).Scan(&n)
		return n
	}
	if count("queued") != 1 || count("done") != 1 {
		t.Fatalf("准备数据有误: queued=%d done=%d", count("queued"), count("done"))
	}
	// 默认:清掉排队 / 失败 / 已取消,保留已完成待替换和已替换
	_, b := e.do("POST", "/api/v1/admin/compress/clear", map[string]any{}, hdr)
	if !strings.Contains(string(b), `"removed":3`) {
		t.Fatalf("clear: %s", b)
	}
	if count("queued")+count("failed")+count("cancelled") != 0 || count("done") != 1 || count("applied") != 1 {
		t.Fatal("默认只清排队 / 失败 / 已取消")
	}
	if _, err := os.Stat(out); err != nil {
		t.Fatal("已完成待替换的压缩版文件默认要保留")
	}
	// includeDone:连已完成的一起清,并删掉压缩版文件;applied 仍然保留
	_, b = e.do("POST", "/api/v1/admin/compress/clear", map[string]any{"includeDone": true}, hdr)
	if !strings.Contains(string(b), `"removed":1`) {
		t.Fatalf("clear includeDone: %s", b)
	}
	if count("done") != 0 || count("applied") != 1 {
		t.Fatal("includeDone 清掉 done,但 applied 历史不动")
	}
	if _, err := os.Stat(out); err == nil {
		t.Fatal("includeDone 时压缩版输出文件应该被删除")
	}
	// 原视频始终不受影响
	if _, err := os.Stat(big); err != nil {
		t.Fatal("原视频不能被动")
	}
}

// 校验时 ffmpeg 的无害警告(采样数不对、时间戳不单调…)不再让压缩结果被判失败;真正的损坏迹象才算。
func TestFatalDecodeLine(t *testing.T) {
	for _, l := range []string{
		"[in#0 @ 00000000005d0080] wrong sample count",
		"[mp4 @ 0x1] Non-monotonic DTS in output stream 0:1; previous: 100, current: 90",
		"[aac @ 0x2] Application provided invalid, non monotonically increasing dts to muxer",
		"", "   ",
	} {
		if fatalDecodeLine(l) {
			t.Errorf("无害警告不应判失败: %q", l)
		}
	}
	for _, l := range []string{
		"[h264 @ 0x1] error while decoding MB 10 20, bytestream -5",
		"[mov,mp4,m4a,3gp,3g2,mj2 @ 0x1] moov atom not found",
		"Invalid data found when processing input",
		"[hevc @ 0x3] Could not find ref with POC 12",
	} {
		if !fatalDecodeLine(l) {
			t.Errorf("真正的损坏迹象应判失败: %q", l)
		}
	}
	if r := ffmpegReason("\n[out#0/mp4 @ 0x1] x\nError opening output file\nConversion failed!"); r != "Error opening output file" {
		t.Errorf("ffmpegReason = %q", r)
	}
}

// 压缩校验通过后自动替换原文件:原文件进回收区、原位置是压缩版,任务变成 applied。
func TestCompressAutoReplace(t *testing.T) {
	e := newEnv(t)
	e.login()
	hdr := adminHdr(e)
	e.srv.Cfg.Video.HWAccel = "none"
	e.srv.Cfg.Optimize.AutoReplace = true
	dir := filepath.Join(e.lib, "自动替换")
	os.MkdirAll(dir, 0o755)
	big := filepath.Join(dir, "auto.mp4")
	if out, err := exec.Command(e.srv.Cfg.Tools.FFmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30", "-f", "lavfi", "-i", "sine=f=440:d=6",
		"-t", "6", "-c:v", "libx264", "-b:v", "6M", "-minrate", "6M", "-maxrate", "6M", "-bufsize", "6M", "-c:a", "aac", big).CombinedOutput(); err != nil {
		t.Skipf("无法生成样本: %v %s", err, out)
	}
	rescanAll(t, e)
	id := idByName(t, e, "auto.mp4")
	var origSize int64
	_ = e.srv.DB.QueryRow(`SELECT size FROM media WHERE id=?`, id).Scan(&origSize)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	e.srv.StartOptimizeWorker(ctx)
	if resp, _ := e.do("POST", "/api/v1/admin/compress/enqueue", map[string]any{"ids": []int64{id}, "codec": "h265", "mode": "abs", "value": 800, "preset": "veryfast"}, hdr); resp.StatusCode != 200 {
		t.Fatalf("enqueue = %d", resp.StatusCode)
	}
	state := func() (st, errMsg string) {
		_ = e.srv.DB.QueryRow(`SELECT state, err FROM opt_job ORDER BY id DESC LIMIT 1`).Scan(&st, &errMsg)
		return
	}
	deadline := time.Now().Add(180 * time.Second)
	for time.Now().Before(deadline) {
		if st, _ := state(); st == "applied" || st == "failed" {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if st, em := state(); st != "applied" {
		t.Fatalf("校验通过后应该自动替换(applied),实际 %s %s", st, em)
	}
	st, err := os.Stat(big)
	if err != nil || st.Size() >= origSize/2 {
		t.Fatalf("原位置应该是明显变小的压缩版: %v", err)
	}
	out, _ := exec.Command(e.srv.Cfg.Tools.FFprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", big).Output()
	if f := strings.Fields(string(out)); len(f) == 0 || f[0] != "hevc" {
		t.Fatalf("压缩版应该是 hevc: %q", out)
	}
	// 原文件在回收区(默认不永久删除)
	var found bool
	trash := filepath.Join(e.lib, ".mediahub", "trash")
	_ = filepath.WalkDir(trash, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && d.Name() == "auto.mp4" {
			if fi, _ := d.Info(); fi != nil && fi.Size() == origSize {
				found = true
			}
		}
		return nil
	})
	if !found {
		t.Fatal("原文件应该在回收区里")
	}
}
