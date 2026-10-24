package dev.lovkvist.listening.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Light or dark for the Material You theme; the palette themes are one or the other. */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * A palette in the style of daisyUI's themes (https://daisyui.com, MIT), the ones Kiku offers:
 * three base shades for the page and cards, their text color, and the brand colors.
 * Turned into a Material 3 color scheme by [colorScheme].
 */
data class Palette(
    val id: String,
    val name: String,
    val dark: Boolean,
    val base100: Long,
    val base200: Long,
    val base300: Long,
    val content: Long,
    val primary: Long,
    val secondary: Long,
    val accent: Long,
    val error: Long,
) {
    fun colorScheme(): ColorScheme {
        val b1 = Color(base100)
        val b2 = Color(base200)
        val b3 = Color(base300)
        val bc = Color(content)
        val p = Color(primary)
        val s = Color(secondary)
        val a = Color(accent)
        val e = Color(error)
        fun container(c: Color) = lerp(b1, c, 0.25f)
        val muted = lerp(b1, bc, 0.7f)
        return if (dark) {
            darkColorScheme(
                primary = p, onPrimary = on(p), primaryContainer = container(p), onPrimaryContainer = bc,
                secondary = s, onSecondary = on(s), secondaryContainer = container(s), onSecondaryContainer = bc,
                tertiary = a, onTertiary = on(a), tertiaryContainer = container(a), onTertiaryContainer = bc,
                error = e, onError = on(e), errorContainer = container(e), onErrorContainer = bc,
                background = b1, onBackground = bc, surface = b1, onSurface = bc,
                surfaceVariant = b2, onSurfaceVariant = muted, surfaceTint = p,
                inverseSurface = bc, inverseOnSurface = b1, inversePrimary = p,
                outline = lerp(b1, bc, 0.4f), outlineVariant = b3,
                surfaceBright = b3, surfaceDim = b1,
                surfaceContainerLowest = b1, surfaceContainerLow = lerp(b1, b2, 0.5f), surfaceContainer = b2,
                surfaceContainerHigh = lerp(b2, b3, 0.5f), surfaceContainerHighest = b3,
            )
        } else {
            lightColorScheme(
                primary = p, onPrimary = on(p), primaryContainer = container(p), onPrimaryContainer = bc,
                secondary = s, onSecondary = on(s), secondaryContainer = container(s), onSecondaryContainer = bc,
                tertiary = a, onTertiary = on(a), tertiaryContainer = container(a), onTertiaryContainer = bc,
                error = e, onError = on(e), errorContainer = container(e), onErrorContainer = bc,
                background = b1, onBackground = bc, surface = b1, onSurface = bc,
                surfaceVariant = b2, onSurfaceVariant = muted, surfaceTint = p,
                inverseSurface = bc, inverseOnSurface = b1, inversePrimary = p,
                outline = lerp(b1, bc, 0.4f), outlineVariant = b3,
                surfaceBright = b1, surfaceDim = b3,
                surfaceContainerLowest = b1, surfaceContainerLow = lerp(b1, b2, 0.5f), surfaceContainer = b2,
                surfaceContainerHigh = lerp(b2, b3, 0.5f), surfaceContainerHighest = b3,
            )
        }
    }

    /** The four dots on the theme picker's tile. */
    val swatches: List<Color> get() = listOf(primary, secondary, accent, content).map(::Color)

    companion object {
        /** Readable text on a colored fill: near-black on light colors, white on dark ones. */
        private fun on(c: Color) = if (c.luminance() > 0.4f) Color(0xFF1A1A1A) else Color.White

        /** Dynamic colors from the wallpaper (Android 12+); not a palette. */
        const val MATERIAL_YOU = "material"

        // Base shades of dark themes go lighter (200, 300) so cards stand out from the page.
        val all = listOf(
            Palette("light", "light", false, 0xFFFFFFFF, 0xFFF2F2F2, 0xFFE5E6E6, 0xFF1F2937, 0xFF605DFF, 0xFFF43098, 0xFF00D3BB, 0xFFFF6266),
            Palette("dark", "dark", true, 0xFF1D232A, 0xFF262D35, 0xFF303841, 0xFFA6ADBB, 0xFF605DFF, 0xFFF43098, 0xFF00D3BB, 0xFFFF6266),
            Palette("cupcake", "cupcake", false, 0xFFFAF7F5, 0xFFEFEAE6, 0xFFE7E2DF, 0xFF291334, 0xFF65C3C8, 0xFFEF9FBC, 0xFFEEAF3A, 0xFFE4626F),
            Palette("emerald", "emerald", false, 0xFFFFFFFF, 0xFFE8E8E8, 0xFFD1D1D1, 0xFF333C4D, 0xFF66CC8A, 0xFF377CFB, 0xFFF68067, 0xFFFF5861),
            Palette("retro", "retro", false, 0xFFECE3CA, 0xFFE4D8B4, 0xFFD2C59D, 0xFF282425, 0xFFEF9995, 0xFFA4CBB4, 0xFFDC8850, 0xFFE45F58),
            Palette("valentine", "valentine", false, 0xFFFAE7F4, 0xFFF4D6EA, 0xFFEAC3DD, 0xFF632C3B, 0xFFE96D7B, 0xFFA991F7, 0xFF66B1B3, 0xFFE45F58),
            Palette("nord", "nord", false, 0xFFECEFF4, 0xFFE5E9F0, 0xFFD8DEE9, 0xFF2E3440, 0xFF5E81AC, 0xFF81A1C1, 0xFF88C0D0, 0xFFBF616A),
            Palette("winter", "winter", false, 0xFFFFFFFF, 0xFFF2F7FF, 0xFFE3E9F4, 0xFF394E6A, 0xFF0069FF, 0xFF463AA2, 0xFFC148AC, 0xFFE58B8B),
            Palette("lofi", "lofi", false, 0xFFFFFFFF, 0xFFF2F2F2, 0xFFE6E5E5, 0xFF000000, 0xFF0D0D0D, 0xFF1A1919, 0xFF262626, 0xFFDF2E2E),
            Palette("cyberpunk", "cyberpunk", false, 0xFFFFF248, 0xFFE8DB41, 0xFFD1C53A, 0xFF1A1700, 0xFFFF7598, 0xFF75D1F0, 0xFFC07EEC, 0xFFFF5861),
            Palette("dracula", "dracula", true, 0xFF282A36, 0xFF323443, 0xFF3C3F51, 0xFFF8F8F2, 0xFFFF79C6, 0xFFBD93F9, 0xFFFFB86C, 0xFFFF5555),
            Palette("synthwave", "synthwave", true, 0xFF1A103D, 0xFF221551, 0xFF2D1B69, 0xFFF9F7FD, 0xFFE779C1, 0xFF58C7F3, 0xFFF3CC30, 0xFFEC8C78),
            Palette("night", "night", true, 0xFF0F172A, 0xFF162033, 0xFF1E293B, 0xFFCBD5E1, 0xFF38BDF8, 0xFF818CF8, 0xFFF471B5, 0xFFFB7085),
            Palette("coffee", "coffee", true, 0xFF20161F, 0xFF2A1E29, 0xFF3A2A38, 0xFFC59F60, 0xFFDB924B, 0xFF4F8A8B, 0xFF10576D, 0xFFFC9581),
            Palette("forest", "forest", true, 0xFF171212, 0xFF221B1B, 0xFF2D2424, 0xFFCAC9C9, 0xFF1EB854, 0xFF1DB88E, 0xFF1DB8AB, 0xFFF87272),
            Palette("dim", "dim", true, 0xFF2A303C, 0xFF343B48, 0xFF3D4553, 0xFFB2CCD6, 0xFF9FE88D, 0xFFFF7D5C, 0xFFC792E9, 0xFFFFAE9B),
            Palette("sunset", "sunset", true, 0xFF121C22, 0xFF1A252C, 0xFF223038, 0xFF9FB9D0, 0xFFFF865B, 0xFFFD6F9C, 0xFFB387FA, 0xFFFFBBBD),
            Palette("aqua", "aqua", true, 0xFF345DA7, 0xFF3D67B2, 0xFF4672BD, 0xFFE6EEFA, 0xFF09ECF3, 0xFF966FB3, 0xFFFFE999, 0xFFFF7265),
            Palette("halloween", "halloween", true, 0xFF212121, 0xFF2C2C2C, 0xFF383838, 0xFFD6D6D6, 0xFFF28C18, 0xFF6D3A9C, 0xFF51A800, 0xFFF35248),
        )

        fun byId(id: String?): Palette? = all.firstOrNull { it.id == id }
    }
}
