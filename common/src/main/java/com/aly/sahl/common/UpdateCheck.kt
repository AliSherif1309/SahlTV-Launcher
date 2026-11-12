package com.aly.sahl.common

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded update before it is installed:
 *  1. its SHA-256 fingerprint matches the one published with the release (version.json / SHA256SUMS),
 *     so a damaged, cut-off or swapped file is never installed;
 *  2. it really is the expected app;
 *  3. it is signed with the same key as this app (the Sahl key), even for an app that is not installed yet
 *     (when this Android version can read that from a file; otherwise the fingerprint must match).
 * Returns null when everything is fine, otherwise a short reason.
 */
object UpdateCheck {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val b = ByteArray(64 * 1024); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signers(ctx: Context, pkg: String?, file: File?): Set<String> {
        val pm = ctx.packageManager
        val info = if (file != null) pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                   else pm.getPackageInfo(pkg!!, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info?.signingInfo ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun verify(ctx: Context, file: File, expectedSha: String, expectedPkg: String): String? {
        if (!file.isFile || file.length() == 0L) return "empty download"
        if (expectedSha.isNotEmpty() && !LanGuard.same(sha256(file), expectedSha.lowercase())) return "fingerprint does not match"
        val pm = ctx.packageManager
        val pkg = pm.getPackageArchiveInfo(file.path, 0)?.packageName ?: return "not an app file"
        if (pkg != expectedPkg) return "unexpected app ($pkg)"
        val mine = try { signers(ctx, ctx.packageName, null) } catch (_: Exception) { emptySet() }
        val theirs = try { signers(ctx, null, file) } catch (_: Exception) { emptySet() }
        if (theirs.isNotEmpty() && mine.isNotEmpty()) { if (mine.intersect(theirs).isEmpty()) return "not signed with the Sahl key" }
        // Some Android versions cannot read the signature of a downloaded file: then the published fingerprint must
        // have matched (checked above). For updates Android itself also refuses anything not signed with the same key.
        else if (expectedSha.isEmpty()) return "cannot check the signature"
        return null
    }
}
