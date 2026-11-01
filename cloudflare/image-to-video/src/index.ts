export interface Env {
  readonly JARVIS_TOKEN: string;
  readonly HF_SPACE_BASE: string;
  readonly HF_FN_NAME: string;
}

const NEGATIVE =
  "色调艳丽, 过曝, 静态, 细节模糊不清, 字幕, 风格, 作品, 画作, 画面, 静止, 整体发灰, " +
  "最差质量, 低质量, JPEG压缩残留, 丑陋的, 残缺的, 多余的手指, 画得不好的手部, " +
  "画得不好的脸部, 畸形的, 毁容的, 形态畸形的肢体, 手指融合, 静止不动的画面, " +
  "杂乱的背景, 三条腿, 背景人很多, 倒着走";

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (req.headers.get("Authorization") !== `Bearer ${env.JARVIS_TOKEN}`) {
      return json({ error: "Unauthorized" }, 401);
    }
    if (req.method === "GET" && url.pathname === "/health") return json({ ok: true });
    if (req.method !== "POST" || url.pathname !== "/generate") return json({ error: "Not found" }, 404);
    try {
      return await generate(req, env);
    } catch (e) {
      return json({ error: e instanceof Error ? e.message : String(e) }, 500);
    }
  },
};

async function generate(req: Request, env: Env): Promise<Response> {
  const form = await req.formData();
  const image = form.get("image");
  const raw = form.get("params");
  if (!(image instanceof File)) return json({ error: "Missing image" }, 400);
  if (image.size > 8 * 1024 * 1024) return json({ error: "Image must be 8 MB or smaller" }, 413);
  let params: { prompt?: string; duration_seconds?: number; steps?: number } = {};
  if (typeof raw === "string") {
    try { params = JSON.parse(raw) as typeof params; } catch { /* defaults */ }
  }

  const upload = new FormData();
  upload.append("files", image, image.name || "jarvis-input.png");
  const uploaded = await fetch(`${env.HF_SPACE_BASE}/gradio_api/upload`, { method: "POST", body: upload });
  if (!uploaded.ok) throw new Error(`Image upload failed (${uploaded.status}); the free Space may be sleeping or busy.`);
  const paths = await uploaded.json() as string[];
  if (!Array.isArray(paths) || !paths[0]) throw new Error("Unexpected image upload response.");

  const imagePath = paths[0];
  const fileData = {
    path: imagePath,
    url: `${env.HF_SPACE_BASE}/gradio_api/file=${imagePath}`,
    orig_name: image.name || "jarvis-input.png",
    size: image.size,
    mime_type: image.type || "image/png",
    meta: { _type: "gradio.FileData" },
  };
  const data = [
    fileData, null,
    (params.prompt || "make this image come alive, cinematic motion, smooth animation").trim(),
    clamp(params.steps ?? 6, 1, 12), NEGATIVE,
    clampFloat(params.duration_seconds ?? 3.5, 0.5, 4.5),
    1, 1, 42, true, 6, "UniPCMultistep", 3.0, 16, false, true,
  ];
  const submitted = await fetch(`${env.HF_SPACE_BASE}/gradio_api/call/${env.HF_FN_NAME}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ data }),
  });
  if (!submitted.ok) throw new Error(`Job submission failed (${submitted.status}).`);
  const job = await submitted.json() as { event_id?: string };
  if (!job.event_id) throw new Error("The video service returned no job ID.");

  const stream = await fetch(`${env.HF_SPACE_BASE}/gradio_api/call/${env.HF_FN_NAME}/${job.event_id}`, {
    headers: { Accept: "text/event-stream" },
  });
  if (!stream.ok || !stream.body) throw new Error(`Could not open result stream (${stream.status}).`);
  return new Response(stream.body, {
    headers: { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache, no-transform", "X-Accel-Buffering": "no" },
  });
}

function clamp(n: number, min: number, max: number): number { return Math.max(min, Math.min(max, Math.round(n))); }
function clampFloat(n: number, min: number, max: number): number { return Math.max(min, Math.min(max, n)); }
function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}
