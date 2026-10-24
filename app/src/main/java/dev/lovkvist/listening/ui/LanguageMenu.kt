package dev.lovkvist.listening.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.lovkvist.listening.R
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.l10n.label
import dev.lovkvist.listening.text.Language

/** Every language, the favorites first; each part sorted by name. */
fun languagesInOrder(favorites: Set<Language>): List<Language> =
    Language.entries.sortedWith(compareBy<Language> { it !in favorites }.thenBy { it.displayName() })

/** A star that adds a language to the favorites or takes it off. */
@Composable
fun FavoriteStar(favorite: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border),
            contentDescription = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The items of a language dropdown: favorites first, then a line, then the rest, each with a
 * star to change the favorites. [close] closes the menu after a choice.
 */
@Composable
fun LanguageMenuItems(
    current: Language,
    favorites: Set<Language>,
    onSelect: (Language) -> Unit,
    onToggleFavorite: (Language) -> Unit,
    close: () -> Unit,
) {
    val ordered = languagesInOrder(favorites)
    for ((i, l) in ordered.withIndex()) {
        if (i > 0 && l !in favorites && ordered[i - 1] in favorites) HorizontalDivider()
        DropdownMenuItem(
            text = { Text(l.label(), fontWeight = if (l == current) FontWeight.SemiBold else FontWeight.Normal) },
            onClick = {
                close()
                if (l != current) onSelect(l)
            },
            trailingIcon = { FavoriteStar(l in favorites) { onToggleFavorite(l) } },
        )
    }
}
