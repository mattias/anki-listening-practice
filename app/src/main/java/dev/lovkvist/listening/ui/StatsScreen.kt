package dev.lovkvist.listening.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.stats.Bucket
import dev.lovkvist.listening.data.stats.Period
import dev.lovkvist.listening.data.stats.Statistics
import dev.lovkvist.listening.data.stats.Totals
import dev.lovkvist.listening.data.stats.formatListenTime
import dev.lovkvist.listening.data.stats.languageLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What was practiced: listening time, sentences and plays, per language and day, week, month, year or all time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsPage(vm: SettingsViewModel) {
    val rows by vm.statRows.collectAsStateWithLifecycle()
    var periodName by rememberSaveable { mutableStateOf(Period.WEEK.name) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    val period = Period.valueOf(periodName)
    val today = LocalDate.now()

    if (rows.none { it.sentences > 0 || it.listenMs > 0 }) {
        Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val languages = Statistics.languages(rows)
    // A language that has no data any more (the list changed) falls back to all.
    val shown = language?.takeIf { it in languages }
    val mine = Statistics.inLanguage(rows, shown)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in Period.entries) {
            FilterChip(selected = p == period, onClick = { periodName = p.name }, label = { Text(stringResource(periodLabel(p))) })
        }
    }
    if (languages.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = shown == null, onClick = { language = null }, label = { Text(stringResource(R.string.stats_all_languages)) })
            for (l in languages) FilterChip(selected = shown == l, onClick = { language = l }, label = { Text(languageLabel(l)) })
        }
    }

    // The group we are in now, in numbers.
    val now = Statistics.current(mine, period, today)
    Text(stringResource(currentLabel(period)), style = MaterialTheme.typography.titleMedium)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatListenTime(now.listenMs), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.stats_listening_time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Figure(now.sentences.toString(), stringResource(R.string.stats_sentences))
                Figure(now.plays.toString(), stringResource(R.string.stats_plays))
                Figure(now.clear.toString(), stringResource(R.string.crystal_clear))
                Figure(now.days.toString(), stringResource(R.string.stats_days))
            }
        }
    }

    // How it went over time.
    if (period != Period.ALL) {
        val buckets = Statistics.buckets(mine, period, today, chartCount(period))
        Text(stringResource(R.string.stats_over_time), style = MaterialTheme.typography.titleMedium)
        Chart(buckets, period)
        val busy = buckets.filter { it.totals.sentences > 0 || it.totals.listenMs > 0 }.reversed()
        for (b in busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(bucketLabel(b, period, long = true), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${formatListenTime(b.totals.listenMs)} · ${stringResource(R.string.stats_sentences_count, b.totals.sentences)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // What the selected group is made of: languages and kinds of source.
    val groupRows = when (period) {
        Period.ALL -> mine
        else -> {
            val start = Statistics.bucketStart(today, period)
            val end = Statistics.buckets(mine, period, today, 1).single().end
            Statistics.between(mine, start, end)
        }
    }
    if (shown == null && languages.size > 1) {
        HorizontalDivider()
        Text(stringResource(R.string.stats_by_language), style = MaterialTheme.typography.titleMedium)
        for ((code, t) in Statistics.byLanguage(groupRows)) Breakdown(languageLabel(code), t)
    }
    HorizontalDivider()
    Text(stringResource(R.string.stats_by_source), style = MaterialTheme.typography.titleMedium)
    for ((kind, t) in Statistics.byKind(groupRows)) Breakdown(stringResource(kindLabel(kind)), t)

    HorizontalDivider()
    Text(stringResource(R.string.stats_export), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.stats_export_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportStats(uri) }
    OutlinedButton(onClick = { save.launch("listening-practice-statistics.json") }) { Text(stringResource(R.string.stats_export_button)) }

}

@Composable
private fun Figure(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Breakdown(label: String, t: Totals) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${formatListenTime(t.listenMs)} · ${stringResource(R.string.stats_sentences_count, t.sentences)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bars of listening time, oldest to newest. */
@Composable
private fun Chart(buckets: List<Bucket>, period: Period) {
    val top = (buckets.maxOfOrNull { it.totals.listenMs } ?: 0L).coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (b in buckets) {
            val fraction = b.totals.listenMs.toFloat() / top
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((96 * fraction).coerceAtLeast(if (b.totals.listenMs > 0) 3f else 1f).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (b.totals.listenMs > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Labels under each bar, or under every second one when there are many.
        val every = if (buckets.size > 12) 2 else 1
        buckets.forEachIndexed { i, b ->
            Text(
                if (i % every == 0 || i == buckets.lastIndex) bucketLabel(b, period, long = false) else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private fun chartCount(period: Period) = when (period) {
    Period.DAY -> 14
    Period.WEEK -> 12
    Period.MONTH -> 12
    Period.YEAR -> 8
    Period.ALL -> 1
}

private fun bucketLabel(b: Bucket, period: Period, long: Boolean): String {
    val locale = Locale.getDefault()
    return when (period) {
        Period.DAY -> if (long) b.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) else b.start.dayOfMonth.toString()
        Period.WEEK -> if (long) b.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) + " →"
        else java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR.let { b.start.get(it).toString() }
        Period.MONTH -> b.start.format(DateTimeFormatter.ofPattern(if (long) "LLLL yyyy" else "LLL", locale))
        Period.YEAR -> b.start.year.toString()
        Period.ALL -> ""
    }
}

private fun periodLabel(p: Period) = when (p) {
    Period.DAY -> R.string.stats_day
    Period.WEEK -> R.string.stats_week
    Period.MONTH -> R.string.stats_month
    Period.YEAR -> R.string.stats_year
    Period.ALL -> R.string.stats_all
}

private fun currentLabel(p: Period) = when (p) {
    Period.DAY -> R.string.stats_period_today
    Period.WEEK -> R.string.stats_this_week
    Period.MONTH -> R.string.stats_this_month
    Period.YEAR -> R.string.stats_this_year
    Period.ALL -> R.string.stats_all_time
}

private fun kindLabel(kind: String) = when (kind) {
    "anki" -> R.string.anki_cards
    "video" -> R.string.videos_audio
    "song" -> R.string.stats_kind_songs
    "sound" -> R.string.sound_training_source
    else -> R.string.stats_kind_other
}
