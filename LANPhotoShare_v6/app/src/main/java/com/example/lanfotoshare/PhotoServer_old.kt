package com.example.lanfotoshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

class PhotoServer(
    private val ctx: Context,
    port: Int,
    initial: List<SelectedItem>,
    private val token: String?
) : NanoHTTPD(port) {

    @Volatile
    private var items: List<SelectedItem> = initial.toList()

    private val thumbCache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // Upload sessions for resumable chunks
    private val uploads = ConcurrentHashMap<String, UploadState>()

    data class UploadState(
        val name: String,
        val mime: String,
        val total: Long,
        val tmp: File,
        @Volatile var received: Long = 0
    )

    fun updateItems(newItems: List<SelectedItem>) {
        items = newItems.toList()
        thumbCache.evictAll()
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            // If first visit has ?t=token, set cookie and redirect to clean URL
            if (session.uri == "/" && session.parameters["t"]?.firstOrNull() != null && token != null) {
                val t = session.parameters["t"]!!.first()
                if (t == token) {
                    val resp = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "redirect")
                    resp.addHeader("Set-Cookie", "pin=$t; Path=/; Max-Age=86400")
                    resp.addHeader("Location", "/")
                    return resp
                }
            }
            if (!checkAuth(session)) return forbidden()

            when (val path = session.uri) {
                "/" -> indexHtml()
                "/sw.js" -> serviceWorkerJs()
                "/file" -> serveFile(session)
                "/zip" -> serveZip()
                "/thumb" -> serveThumb(session)
                "/upload_init" -> uploadInit(session)
                "/upload_chunk" -> uploadChunk(session)
                "/upload_finish" -> uploadFinish(session)
                else -> notFound("Path $path non trovato")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Errore: ${e.message}")
        }
    }

    private fun checkAuth(session: IHTTPSession): Boolean {
        if (token == null) return true
        // Accept either cookie pin= or query t= (for rare direct calls)
        val cookie = session.headers["cookie"] ?: ""
        val okCookie = cookie.split(';').map { it.trim() }.any { it.startsWith("pin=") && it.substringAfter("pin=") == token }
        val okQuery = session.parameters["t"]?.firstOrNull() == token
        return okCookie || okQuery
    }

    private fun indexHtml(): Response {
        val b = StringBuilder().apply {
            append("<!doctype html><html><head><meta charset='utf-8'>")
            append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
            append("<title>LAN Photo Share</title>")
            append("<link rel='manifest' href='data:application/manifest+json,{\"name\":\"LAN Photo Share\",\"start_url\":\"/\",\"display\":\"minimal-ui\"}'>")
            append("<script>if('serviceWorker' in navigator){navigator.serviceWorker.register('/sw.js');}</script>")
            append("<style>body{font-family:sans-serif;max-width:1000px;margin:24px auto;padding:0 12px}")
            append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}")
            append(".card{border:1px solid #ddd;border-radius:8px;padding:10px}")
            append(".thumb{width:100%;height:auto;display:block;border-radius:6px;background:#f5f5f5}")
            append(".top{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;flex-wrap:wrap;gap:8px}")
            append(".upload{border:1px dashed #aaa;border-radius:10px;padding:14px;margin:16px 0}")
            append(".pf{height:6px;background:#ddd;border-radius:6px;overflow:hidden}")
            append(".pf>span{display:block;height:6px;background:#4caf50;width:0%}")
            append(".overall{position:sticky;bottom:0;left:0;right:0;background:#fffccf;border-top:1px solid #ddd;padding:10px;margin-top:18px}")
            append(".row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}")
            append("</style></head><body>")
            append("<div class='top'><h2>Media condivisi (" + items.size + ")</h2>")
            append("<div class='row'><a id='dlzip' href='/zip' download style='margin-right:12px'>📦 Scarica tutto (ZIP)</a>")
            append("<label>Concorrenza upload: <input id='concurrency' type='number' min='1' max='4' value='3' style='width:60px'></label>")
            append("<label>Limite banda: <input id='mbps' type='number' min='0' step='0.1' value='0' style='width:80px'> Mbps (0=illimitato)</label></div></div>")
            // Upload box (images + videos)
            append("<div class='upload'>")
            append("<h3>⬆️ Carica su DCIM/Camera (immagini e video, metadati intatti)</h3>")
            append("<input id='f' type='file' accept='image/*,video/*' multiple/> ")
            append("<button id='btnUp'>Carica</button> ")
            append("<span id='st' style='margin-left:8px;opacity:.8'></span>")
            append("<div id='overall' class='overall' style='display:none'>")
            append("<div>Totale: <span id='ovTxt'>0%</span> • Velocità: <span id='ovSpd'>0 MB/s</span> • Restante: <span id='ovLeft'>--</span></div>")
            append("<div class='pf'><span id='ovBar'></span></div></div>")
            append("<ul id='log' style='margin-top:8px;padding-left:18px'></ul>")
            append("<p style='opacity:.7;margin-top:8px'>Suggerimento Live Photo: seleziona sia la foto (HEIC/JPG) che il relativo MOV per inviarli come coppia.</p>")
            append("</div>")
            // Grid
            append("<div class='grid'>")
            items.forEachIndexed { idx, it ->
                val safe = java.net.URLEncoder.encode(it.name, "UTF-8")
                append("<div class='card'>")
                append("<img class='thumb' loading='lazy' src='/thumb?id=" + idx + "' alt='thumb'/>")
                append("<div style='margin-top:8px'><a class='dl' href='/file?id=" + idx + "&name=" + safe + "' download='" + safe + "'>⬇️ " + escape(it.name) + "</a></div>")
                append("</div>")
            }
            append("</div>")
            append(scriptBlock())
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", b.toString())
    }

    private fun scriptBlock(): String {
        // JS for resumable uploads, throttle, concurrency, overall progress, and SW-based download progress fallback
        return """
<script>
// ---- Utility ----
function fmtBytes(b){ if(b<1024) return b+' B'; if(b<1024*1024) return (b/1024).toFixed(1)+' KB'; if(b<1024*1024*1024) return (b/1024/1024).toFixed(1)+' MB'; return (b/1024/1024/1024).toFixed(2)+' GB'; }
function fmtTime(s){ if(!isFinite(s) || s<=0) return '--'; const h=Math.floor(s/3600), m=Math.floor((s%3600)/60), sec=Math.floor(s%60); return (h? h+'h ' : '') + (m? m+'m ' : '') + sec+'s'; }
function logItem(html) { const li=document.createElement('li'); li.innerHTML=html; log.appendChild(li); li.scrollIntoView(); return li; }

const btn = document.getElementById('btnUp');
const input = document.getElementById('f');
const st = document.getElementById('st');
const log = document.getElementById('log');
const ov = document.getElementById('overall');
const ovTxt = document.getElementById('ovTxt');
const ovBar = document.getElementById('ovBar');
const ovSpd = document.getElementById('ovSpd');
const ovLeft = document.getElementById('ovLeft');
const dlzip = document.getElementById('dlzip');

// Download progress via Service Worker message (fallback to browser default)
if (navigator.serviceWorker) {
  navigator.serviceWorker.addEventListener('message', (e)=>{
    if(e.data && e.data.type==='downloadProgress'){
      const {loaded,total} = e.data;
      ov.style.display='block';
      ovTxt.textContent = Math.floor(100*loaded/total) + '% (' + fmtBytes(loaded) + ' / ' + fmtBytes(total) + ')';
      ovBar.style.width = (100*loaded/total) + '%';
    }
  });
}

// Replace default click on .dl to stream via fetch and show progress (fallback to default on error)
for (const a of document.querySelectorAll('a.dl')) {
  a.addEventListener('click', async (ev)=>{
    try{
      ev.preventDefault();
      const href = a.getAttribute('href');
      const resp = await fetch(href);
      const total = Number(resp.headers.get('Content-Length')) || 0;
      const reader = resp.body.getReader();
      let loaded = 0;
      const chunks = [];
      ov.style.display='block';
      let start = performance.now();
      while(true){
        const {done, value} = await reader.read();
        if(done) break;
        loaded += value.length;
        chunks.push(value);
        const elapsed = (performance.now()-start)/1000;
        const speed = loaded/elapsed;
        ovTxt.textContent = Math.floor(100*loaded/total) + '% (' + fmtBytes(loaded) + ' / ' + fmtBytes(total) + ')';
        ovBar.style.width = (100*loaded/total) + '%';
        ovSpd.textContent = (speed/1024/1024).toFixed(2) + ' MB/s';
        const left = total ? (total-loaded)/(speed||1) : 0;
        ovLeft.textContent = fmtTime(left);
      }
      const blob = new Blob(chunks);
      const url = URL.createObjectURL(blob);
      const fileName = (new URL(href, location)).searchParams.get('name') || 'download';
      const link = document.createElement('a');
      link.href = url; link.download = fileName;
      document.body.appendChild(link); link.click();
      link.remove(); URL.revokeObjectURL(url);
    }catch(e){ location.href = a.href; }
  });
}

// ---- Resumable upload client ----
async function uploadResumable(file, mbpsLimit, onProgress){
  // 1) init
  const init = await fetch('/upload_init', {
    method:'POST',
    headers:{'x-name':file.name,'x-size':String(file.size),'x-mime':file.type||'application/octet-stream'}
  });
  if(!init.ok){ throw new Error(await init.text()); }
  const id = await init.text();

  // 2) chunks
  const chunkSize = 1024*1024; // 1 MB
  let offset = 0;
  const start = performance.now();
  while(offset < file.size){
    const end = Math.min(offset+chunkSize, file.size);
    const chunk = file.slice(offset, end);
    const resp = await fetch('/upload_chunk', {
      method:'POST',
      headers:{'x-id':id,'x-offset':String(offset),'x-total':String(file.size)},
      body: chunk
    });
    if(!resp.ok){ throw new Error(await resp.text()); }
    offset = end;
    // Throttle if needed
    if (mbpsLimit > 0){
      const elapsed = (performance.now() - start)/1000;
      const ideal = (offset/ (mbpsLimit*1024*1024));
      const sleep = ideal - elapsed;
      if (sleep > 0) await new Promise(r=>setTimeout(r, sleep*1000));
    }
    if(onProgress) onProgress(offset, file.size);
  }
  // 3) finish
  const fin = await fetch('/upload_finish', { method:'POST', headers:{'x-id':id} });
  if(!fin.ok){ throw new Error(await fin.text()); }
}

btn.onclick = async () => {
  const files = input.files;
  if (!files || files.length===0) { alert('Seleziona una o più immagini o video'); return; }
  ov.style.display='block';
  st.textContent = 'Caricamento in corso...';

  const list = Array.from(files);
  // Try to group Live Photos by stem (IMG_1234.{heic,mov})
  const groups = {};
  for (const f of list){
    const m = f.name.match(/^(.*)\\.(\\w+)$/i);
    const stem = m ? m[1] : f.name;
    (groups[stem] = groups[stem] || []).push(f);
  }
  const ordered = Object.values(groups).flat(); // image+mov adiacenti nel log

  const totalSize = ordered.reduce((a,f)=>a+f.size,0);
  let sentTotal = 0;
  let startTs = performance.now();
  const concurrency = Math.max(1, Math.min(4, Number(document.getElementById('concurrency').value||3)));
  const mbps = Math.max(0, Number(document.getElementById('mbps').value||0));

  let index = 0;
  const runners = new Array(concurrency).fill(0).map(()=> (async function runner(){
    while(index < ordered.length){
      const i = index++;
      const f = ordered[i];
      const row = logItem(`<b>${f.name}</b> — <span class='p'>0%</span><div class='pf'><span class='bar'></span></div>`);
      const pctEl = row.querySelector('.p');
      const barEl = row.querySelector('.bar');

      try{
        await uploadResumable(f, mbps, (loaded,total)=>{
          const pct = Math.floor(100*loaded/total);
          pctEl.textContent = pct + '%';
          barEl.style.width = pct + '%';
          const now = performance.now();
          const elapsed = (now - startTs)/1000.0;
          const currentTotal = sentTotal + loaded;
          const speed = currentTotal/elapsed;
          const remain = totalSize - currentTotal;
          ovTxt.textContent = Math.floor(100*currentTotal/totalSize) + '% (' + fmtBytes(currentTotal) + ' / ' + fmtBytes(totalSize) + ')';
          ovBar.style.width = (100*currentTotal/totalSize) + '%';
          ovSpd.textContent = (speed/1024/1024).toFixed(2) + ' MB/s';
          ovLeft.textContent = fmtTime(remain / (speed||1));
        });
        sentTotal += f.size;
      }catch(e){
        row.innerHTML += ' — <span style="color:red">ERRORE: '+e+'</span>';
      }
    }
  })());

  await Promise.all(runners);
  st.textContent = 'Fatto.';
};
</script>
""".trimIndent()
    }

    private fun serviceWorkerJs(): Response {
        val js = """
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (url.pathname === '/zip' || url.pathname === '/file') {
    event.respondWith((async () => {
      const resp = await fetch(event.request);
      const total = Number(resp.headers.get('Content-Length')) || 0;
      if (total > 0 && resp.body) {
        const reader = resp.body.getReader();
        let loaded = 0;
        const stream = new ReadableStream({
          async pull(controller) {
            const {done, value} = await reader.read();
            if (done) { controller.close(); return; }
            loaded += value.length;
            self.clients.matchAll().then(cs => cs.forEach(c => c.postMessage({type:'downloadProgress', loaded, total})));
            controller.enqueue(value);
          }
        });
        return new Response(stream, { headers: resp.headers });
      }
      return resp;
    })());
  }
});
        """.strip()
        return newFixedLengthResponse(Response.Status.OK, "application/javascript", js)
    }

    private fun uploadInit(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val name = session.headers["x-name"] ?: return badRequest("x-name mancante")
        val size = session.headers["x-size"]?.toLongOrNull() ?: return badRequest("x-size mancante")
        val mime = session.headers["x-mime"] ?: "application/octet-stream"
        val tmp = File.createTempFile("up_", ".part", ctx.cacheDir)
        val id = UUID.randomUUID().toString()
        uploads[id] = UploadState(name, mime, size, tmp, 0)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", id)
    }

    private fun uploadChunk(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val id = session.headers["x-id"] ?: return badRequest("x-id mancante")
        val off = session.headers["x-offset"]?.toLongOrNull() ?: return badRequest("x-offset mancante")
        val total = session.headers["x-total"]?.toLongOrNull() ?: return badRequest("x-total mancante")
        val st = uploads[id] ?: return notFound("upload non trovato")
        if (st.total != total) return badRequest("total mismatch")

        val bodyFile = File.createTempFile("chunk_", ".bin", ctx.cacheDir)
        val files = HashMap<String, String>()
        // Raw body - NanoHTTPD provides input stream in session.inputStream; we copy to temp
        val lenHeader = session.headers["content-length"]?.toLongOrNull()
        val input = session.inputStream
        FileOutputStream(bodyFile).use { out ->
            val buf = ByteArray(64*1024)
            var left = lenHeader ?: -1L
            while (left != 0L) {
                val r = input.read(buf, 0, if (left<0) buf.size else minOf(buf.size.toLong(), left).toInt())
                if (r <= 0) break
                out.write(buf, 0, r)
                if (left>0) left -= r
            }
        }

        RandomAccessFile(st.tmp, "rw").use { raf ->
            if (raf.length() != off) {
                // allow resume forward only
            }
            raf.seek(off)
            FileInputStream(bodyFile).use { it.copyTo(raf) }
        }
        bodyFile.delete()
        st.received = max(st.received, off + (lenHeader ?: 0))
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
    }

    private fun uploadFinish(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val id = session.headers["x-id"] ?: return badRequest("x-id mancante")
        val st = uploads.remove(id) ?: return notFound("upload non trovato")
        // move into MediaStore
        FileInputStream(st.tmp).use { ins ->
            val ok = SaveMedia.saveToCamera(ctx.contentResolver, sanitizeFilename(st.name), st.mime, ins)
            st.tmp.delete()
            if (!ok) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "salvataggio fallito")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "done")
    }

    private fun serveFile(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: return badRequest("id mancante")
        if (id !in items.indices) return notFound("Elemento non trovato")

        val item = items[id]
        val afd = ctx.contentResolver.openAssetFileDescriptor(item.uri, "r")
            ?: return notFound("Impossibile aprire il file")
        val length = afd.length
        val input = FileInputStream(afd.fileDescriptor)

        val resp: Response = if (length >= 0) {
            newFixedLengthResponse(Response.Status.OK, item.mime, input, length)
        } else {
            newChunkedResponse(Response.Status.OK, item.mime, input)
        }
        val fname = (session.parameters["name"]?.firstOrNull() ?: item.name).replace("\"", "")
        resp.addHeader("Content-Disposition", "attachment; filename=\"$fname\"")
        return resp
    }

    private fun serveZip(): Response {
        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)
        Thread {
            ZipOutputStream(BufferedOutputStream(pipedOut)).use { zos ->
                items.forEach { it ->
                    val name = sanitizeZipEntryName(it.name)
                    zos.putNextEntry(ZipEntry(name))
                    ctx.contentResolver.openInputStream(it.uri)?.use { ins ->
                        ins.copyTo(zos, 64 * 1024)
                    }
                    zos.closeEntry()
                }
            }
        }.start()
        val resp = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
        resp.addHeader("Content-Disposition", "attachment; filename=\"media.zip\"")
        return resp
    }

    private fun serveThumb(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: return badRequest("id mancante")
        if (id !in items.indices) return notFound("Elemento non trovato")

        val key = "id:$id@240"
        thumbCache.get(key)?.let { bytes ->
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        val item = items[id]
        val bytes = if (item.mime.startsWith("video/")) {
            createVideoThumbnail(item.uri, 240)
        } else {
            createImageThumbnail(item.uri, 240)
        } ?: return notFound("Thumb non disponibile")

        thumbCache.put(key, bytes)
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun createImageThumbnail(uri: android.net.Uri, target: Int): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        val longSide = max(w, h)
        while (longSide / sample > target * 4) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src: Bitmap = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val scale = target.toFloat() / max(src.width, src.height).toFloat()
        val outW = kotlin.math.max(1, (src.width * scale).toInt())
        val outH = kotlin.math.max(1, (src.height * scale).toInt())
        val bmp = android.graphics.Bitmap.createScaledBitmap(src, outW, outH, true)
        if (bmp !== src) src.recycle()

        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private fun createVideoThumbnail(uri: android.net.Uri, target: Int): ByteArray? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val frame = mmr.getFrameAtTime(1_000_000) ?: return null
            val w = frame.width
            val h = frame.height
            val scale = target.toFloat() / max(w, h).toFloat()
            val outW = kotlin.math.max(1, (w * scale).toInt())
            val outH = kotlin.math.max(1, (h * scale).toInt())
            val bmp = android.graphics.Bitmap.createScaledBitmap(frame, outW, outH, true)
            if (bmp !== frame) frame.recycle()
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
            bmp.recycle()
            return bos.toByteArray()
        } catch (e: Exception) {
            return null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun sanitizeZipEntryName(name: String): String {
        return name.replace("\\", "_").replace("/", "_")
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }

    private fun notFound(msg: String) =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", msg)

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", msg)

    private fun methodNotAllowed() =
        newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=utf-8", "Metodo non consentito")

    private fun forbidden() =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8", "PIN richiesto o non valido")
}
