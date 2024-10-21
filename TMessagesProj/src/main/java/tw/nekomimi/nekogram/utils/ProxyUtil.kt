@file:Suppress("UNCHECKED_CAST")

package tw.nekomimi.nekogram.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.setPadding
import com.github.shadowsocks.plugin.PluginOptions
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.v2ray.ang.V2RayConfig.SS_PROTOCOL
import com.v2ray.ang.V2RayConfig.WSS_PROTOCOL
import com.v2ray.ang.V2RayConfig.WS_PROTOCOL
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.telegram.messenger.*
import org.telegram.messenger.browser.Browser
import org.yaml.snakeyaml.Yaml
import tw.nekomimi.nekogram.proxy.ShadowsocksLoader
import tw.nekomimi.nekogram.proxynext.ProxyConfig
import tw.nekomimi.nekogram.proxynext.ShadowsocksBean
import tw.nekomimi.nekogram.ui.BottomBuilder
import tw.nekomimi.nekogram.utils.AlertUtil.showToast
import java.io.File
import java.util.*


object ProxyUtil {
    @SuppressLint("NewApi")
    @JvmStatic
    @JvmOverloads
    fun parseProxies(text: String, tryDecode: Boolean = true): MutableList<String> {

        if (tryDecode && !text.contains("proxies:")) {
            try {
                return parseProxies(String(Base64.decode(text, Base64.NO_PADDING)), false)
            } catch (ignored: Exception) {
            }
        }

        val proxies = mutableListOf<String>()

        try {
            // sip008
            val ssArray = JSONArray(text)
            for (index in 0 until ssArray.length()) {
                proxies.add(ShadowsocksLoader.Bean.parseJson(ssArray.getJSONObject(index)).toString())
            }
            return proxies
        } catch (ignored: JSONException) {
        }

        if (text.contains("proxies:")) {
            // clash

            val yamlProxies = Yaml().loadAs(text, Map::class.java)["proxies"] as List<Map<String, Any?>>
            for (proxy in yamlProxies) {
                runCatching {
                    when (proxy["type"] as String) {
                        "ss" -> {
                            var pluginStr = ""
                            if (proxy.contains("plugin")) {
                                val opts = PluginOptions()
                                opts.id = proxy["plugin"] as String
                                opts.putAll(proxy["plugin-opts"] as Map<String, String?>)
                                pluginStr = opts.toString(false)
                            }
                            proxies.add(
                                    ShadowsocksLoader.Bean(
                                            proxy["server"] as String,
                                            proxy["port"] as Int,
                                            proxy["password"] as String,
                                            proxy["cipher"] as String,
                                            pluginStr,
                                            proxy["name"] as String
                                    ).toString())
                        }
                        else -> {
                            // ignored
                        }
                    }
                }
            }
            return proxies
        }

        text.split('\n').map { it.split(" ") }.forEach {

            it.forEach { line ->

                if (line.startsWith("tg://proxy") ||
                        line.startsWith("tg://socks") ||
                        line.startsWith("https://t.me/proxy") ||
                        line.startsWith("https://t.me/socks") ||
                        line.startsWith(SS_PROTOCOL) ||
                        line.startsWith(WS_PROTOCOL) ||
                        line.startsWith(WSS_PROTOCOL)) {
                    runCatching { proxies.add(SharedConfig.parseProxyInfo(line).link) }
                }
            }
        }
        if (proxies.isEmpty()) error("no proxy link found")
        return proxies
    }

    @JvmStatic
    fun importFromClipboard(ctx: Activity) {
        val text = (ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
        val proxies = mutableListOf<SharedConfig.ProxyInfo>()
        var error = false

        text?.trim()?.split('\n')?.map { it.split(" ") }?.forEach { lists ->
            lists.forEach { line ->
                if (line.startsWith("tg://proxy") ||
                        line.startsWith("tg://socks") ||
                        line.startsWith("https://t.me/proxy") ||
                        line.startsWith("https://t.me/socks") ||
                        line.startsWith(SS_PROTOCOL) ||
                        line.startsWith(WS_PROTOCOL) ||
                        line.startsWith(WSS_PROTOCOL)) {
                    runCatching { proxies.add(SharedConfig.parseProxyInfo(line)) }.onFailure {
                        error = true
                        showToast(LocaleController.getString(R.string.BrokenLink) + ": ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            }
        }

        runCatching {
            if (proxies.isEmpty() && !error) {
                String(Base64.decode(text, Base64.NO_PADDING)).trim().split('\n').map { it.split(" ") }.forEach { str ->
                    str.forEach { line ->
                        if (line.startsWith("tg://proxy") ||
                                line.startsWith("tg://socks") ||
                                line.startsWith("https://t.me/proxy") ||
                                line.startsWith("https://t.me/socks") ||
                                line.startsWith(SS_PROTOCOL) ||
                                line.startsWith(WS_PROTOCOL) ||
                                line.startsWith(WSS_PROTOCOL)) {
                            runCatching { proxies.add(SharedConfig.parseProxyInfo(line)) }.onFailure {
                                error = true
                                showToast(LocaleController.getString(R.string.BrokenLink) + ": ${it.message ?: it.javaClass.simpleName}")
                            }
                        }
                    }
                }
            }
        }

        if (proxies.isEmpty()) {
            if (!error) showToast(LocaleController.getString(R.string.BrokenLink))
            return
        } else if (!error) {
            AlertUtil.showSimpleAlert(ctx, LocaleController.getString(R.string.ImportedProxies) + "\n\n" + proxies.joinToString("\n") { it.title })
        }

        proxies.forEach {
            SharedConfig.addProxy(it)
        }

        UIUtil.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        }
    }

    @JvmStatic
    fun importProxy(ctx: Context, link: String): Boolean {
        runCatching {
            if (link.startsWith(ProxyConfig.SS_PROTOCOL)) {
                when (val singConfig = ProxyConfig.parseSingBoxConfig(link)) {
                    is ShadowsocksBean -> AndroidUtilities.showShadowsocksAlert(ctx, singConfig)
                }
            } else if (link.startsWith(ProxyConfig.WS_PROTOCOL) || link.startsWith(ProxyConfig.WSS_PROTOCOL)) {
                AndroidUtilities.showWsAlert(ctx, SharedConfig.WsProxy(link))
            } else {
                val url = link.replace("tg://", "https://t.me/").toHttpUrlOrNull()!!
                AndroidUtilities.showProxyAlert(ctx,
                        url.queryParameter("server") ?: return false,
                        url.queryParameter("port") ?: return false,
                        url.queryParameter("user"),
                        url.queryParameter("pass"),
                        url.queryParameter("secret"),
                        url.fragment)
            }
            return true
        }.onFailure {
            FileLog.e(it)
            if (BuildVars.LOGS_ENABLED) {
                AlertUtil.showSimpleAlert(ctx, it)
            } else {
                showToast("${LocaleController.getString(R.string.BrokenLink)}: ${it.message}")
            }

        }

        return false

    }

    @JvmStatic
    fun shareProxy(ctx: Activity, info: SharedConfig.ProxyInfo, type: Int) {

        val url = info.link;

        if (type == 1) {

            AndroidUtilities.addToClipboard(url)

            Toast.makeText(ctx, LocaleController.getString(R.string.LinkCopied), Toast.LENGTH_LONG).show()

        } else if (type == 0) {

            val shareIntent = Intent(Intent.ACTION_SEND)

            shareIntent.type = "text/plain"

            shareIntent.putExtra(Intent.EXTRA_TEXT, url)

            val chooserIntent = Intent.createChooser(shareIntent, LocaleController.getString(R.string.ShareLink))

            chooserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            ctx.startActivity(chooserIntent)

        } else {

            showQrDialog(ctx, url)

        }

    }

    @JvmStatic
    fun getOwnerActivity(ctx: Context): Activity {

        if (ctx is Activity) return ctx

        if (ctx is ContextWrapper) return getOwnerActivity(ctx.baseContext)

        error("unable cast ${ctx.javaClass.name} to activity")

    }

    @JvmStatic
    @JvmOverloads
    fun showQrDialog(ctx: Context, text: String, icon: ((Int) -> Bitmap)? = null): AlertDialog {

        val code = createQRCode(text, icon = icon)

        ctx.setTheme(R.style.Theme_TMessages)

        return AlertDialog.Builder(ctx).setView(LinearLayout(ctx).apply {

            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)

            addView(LinearLayout(ctx).apply {
                val root = this

                gravity = Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(AndroidUtilities.dp(16f))

                val width = AndroidUtilities.dp(260f)

                addView(ImageView(ctx).apply {

                    setImageBitmap(code)

                    scaleType = ImageView.ScaleType.FIT_XY

                    setOnLongClickListener {

                        val builder = BottomBuilder(ctx)

                        builder.addItems(arrayOf(

                                LocaleController.getString(R.string.SaveToGallery),
                                LocaleController.getString(R.string.Cancel)

                        ), intArrayOf(

                                R.drawable.baseline_image_24,
                                R.drawable.baseline_cancel_24

                        )) { i, _, _ ->

                            if (i == 0) {

                                if (Build.VERSION.SDK_INT >= 23 && ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                                    getOwnerActivity(ctx).requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4)

                                    return@addItems

                                }

                                val saveTo = File(Environment.getExternalStorageDirectory(), "${Environment.DIRECTORY_PICTURES}/share_${text.hashCode()}.jpg")

                                saveTo.parentFile?.mkdirs()

                                runCatching {

                                    saveTo.createNewFile()

                                    saveTo.outputStream().use {

                                        loadBitmapFromView(root).compress(Bitmap.CompressFormat.JPEG, 100, it);

                                    }

                                    AndroidUtilities.addMediaToGallery(saveTo.path)
                                    showToast(LocaleController.getString(R.string.PhotoSavedHint))

                                }.onFailure {
                                    FileLog.e(it)
                                    showToast(it)
                                }

                            }

                        }

                        builder.show()

                        return@setOnLongClickListener true

                    }

                }, LinearLayout.LayoutParams(width, width))

            }, LinearLayout.LayoutParams(-2, -2).apply {

                gravity = Gravity.CENTER

            })

        }).create().apply {

            show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)

        }

    }

    private fun loadBitmapFromView(v: View): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        v.layout(v.left, v.top, v.right, v.bottom)
        v.draw(c)
        return b
    }

    @JvmStatic
    fun createQRCode(text: String, size: Int = 768, icon: ((Int) -> Bitmap)? = null): Bitmap {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints, null, null, icon)
        } catch (e: WriterException) {
            FileLog.e(e);
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
    }

    val qrReader = QRCodeReader()

    @JvmStatic
    fun tryReadQR(ctx: Activity, bitmap: Bitmap) {

        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)

        try {

            val result = try {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), mapOf(
                        DecodeHintType.TRY_HARDER to true
                ))
            } catch (e: NotFoundException) {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())), mapOf(
                        DecodeHintType.TRY_HARDER to true
                ))
            }

            showLinkAlert(ctx, result.text)

        } catch (e: Throwable) {

            showToast(LocaleController.getString(R.string.NoQrFound))

        }

    }

    @JvmStatic
    @JvmOverloads
    fun showLinkAlert(ctx: Activity, text: String, tryInternal: Boolean = true) {

        val builder = BottomBuilder(ctx)

        if (tryInternal) {
            runCatching {
                if (Browser.isInternalUrl(text, booleanArrayOf(false))) {
                    Browser.openUrl(ctx, text)
                    return
                }
            }
        }

        builder.addTitle(text)

        builder.addItems(arrayOf(
                LocaleController.getString(R.string.Open),
                LocaleController.getString(R.string.Copy),
                LocaleController.getString(R.string.ShareQRCode)
        ), intArrayOf(
                R.drawable.baseline_open_in_browser_24,
                R.drawable.baseline_content_copy_24,
                R.drawable.wallet_qr
        )) { which, _, _ ->
            when (which) {
                0 -> Browser.openUrl(ctx, text)
                1 -> {
                    AndroidUtilities.addToClipboard(text)
                    showToast(LocaleController.getString(R.string.LinkCopied))
                }

                else -> showQrDialog(ctx, text)
            }
        }

        builder.show()

    }

}
