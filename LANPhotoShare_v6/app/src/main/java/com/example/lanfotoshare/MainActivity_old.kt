package com.example.lanfotoshare

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lanfotoshare.databinding.ActivityMainBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.NetworkInterface
import java.util.Collections
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selected: MutableList<SelectedItem> = mutableListOf()
    private var port = 8080
    private var token: String? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selected.clear()
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}
                selected.add(
                    SelectedItem(
                        uri = uri,
                        name = getDisplayName(contentResolver, uri) ?: "file.bin",
                        mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    )
                )
            }
            binding.txtCount.text = "${selected.size} elementi selezionati"
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val itemsFromTree = ContentScanner.scanMedia(contentResolver, uri, maxDepth = 3)
        if (itemsFromTree.isEmpty()) {
            Toast.makeText(this, "Nessun file multimediale trovato", Toast.LENGTH_LONG).show()
        } else {
            selected.clear()
            selected.addAll(itemsFromTree)
            binding.txtCount.text = "${selected.size} elementi (da cartella)"
        }
    }

    private val requestNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startServer() }

    private val requestWriteLegacy = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startServer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.edtPin.setText(randomPin())
        binding.btnPick.setOnClickListener { picker.launch(arrayOf("image/*","video/*")) }
        binding.btnPickFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { stopServer() }
    }

    private fun onStartClicked() {
        token = if (binding.swPin.isChecked) {
            val raw = binding.edtPin.text?.toString()?.trim().orEmpty()
            val ok = raw.matches(Regex("^\\d{4,6}$"))
            if (!ok) {
                Toast.makeText(this, "PIN non valido. Inserisci 4–6 cifre.", Toast.LENGTH_LONG).show()
                return
            }
            raw
        } else null

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT < 29) {
            requestWriteLegacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startServer()
        }
    }

    private fun startServer() {
        val ip = localIpAddress() ?: run {
            Toast.makeText(this, "Non riesco a rilevare l'IP sulla LAN", Toast.LENGTH_SHORT).show()
            return
        }
        ShareService.start(this, port, ArrayList(selected), token)
        val base = "http://$ip:$port"
        val mdns = "http://lanphotoshare.local:$port"
        // Passiamo il token in query solo la prima volta: il server imposterà un cookie e redirezionerà a URL "pulito"
        val firstUrl = if (token != null) "$base/?t=$token" else base
        val urlMdns = if (token != null) "$mdns/?t=$token" else mdns

        binding.txtUrl.text = "URL: $firstUrl\nAlternative: $urlMdns"
        binding.imgQr.setImageBitmap(makeQr(firstUrl))
        Toast.makeText(this, "Server attivo. QR pronto per la scansione.", Toast.LENGTH_LONG).show()
    }

    private fun stopServer() {
        ShareService.stop(this)
        binding.txtUrl.text = "URL: —"
        binding.imgQr.setImageBitmap(null)
        Toast.makeText(this, "Server fermato", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private fun localIpAddress(): String? {
        val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in ifaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                val host = addr.hostAddress ?: continue
                if (addr.isLoopbackAddress) continue
                if (host.contains(":")) continue
                if (host.startsWith("10.") ||
                    host.startsWith("192.168.") ||
                    host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
                ) return host
            }
        }
        return null
    }

    private fun makeQr(text: String): Bitmap {
        val size = 720
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun getDisplayName(cr: ContentResolver, uri: Uri): String? {
        return cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
    }

    private fun randomPin(): String = (1..6).joinToString("") { Random.nextInt(0, 10).toString() }
}
