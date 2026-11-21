package dev.brainrotprevention.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.common.readText
import dev.brainrotprevention.ui.format.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Websites and keywords (§7.3, §12): typed input (hoisted: [input], so Save can add what was typed) with a live preview, a domain-vs-keyword choice for URLs with a
 * path, the anywhere-in-URL toggle for keywords, bulk paste and CSV import with added/duplicate/invalid counts.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WebRulesEditor(
    rules: List<WebRule>,
    onChange: (List<WebRule>) -> Unit,
    input: WebInput,
    onInput: (WebInput) -> Unit,
    verb: String = "Blocks",
) {
    val kind = input.kind
    val text = input.text
    val anywhere = input.anywhere
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var bulkOpen by rememberSaveable { mutableStateOf(false) }
    val preview = EditorOps.preview(kind, text, anywhere, verb)
    val context = LocalContext.current
    val scope = rememberCoroutineScope { dev.brainrotprevention.ui.UiGuard.handler }

    fun add(rule: WebRule) {
        if (EditorOps.atLimit(rules)) {
            message = "Not added: ${EditorOps.LIMIT_TEXT}."
            return
        }
        val next = EditorOps.addRule(rules, rule)
        if (next == null) {
            message = "${rule.value} is already in the list."
        } else {
            onChange(next)
            message = null
            onInput(input.copy(text = ""))
        }
    }

    var parsing by remember { mutableStateOf(false) }
    val currentRules by rememberUpdatedState(rules)
    val currentOnChange by rememberUpdatedState(onChange)

    // Parsing a long list (IDN, NFC per line) runs off the main thread; the result is applied on it (adversarial 1).
    fun bulk(input: String) {
        val before = rules
        parsing = true
        scope.launch {
            try {
                val (_, r) = withContext(Dispatchers.Default) { EditorOps.bulk(before, input) }
                // Merged into the list as it is now (it may have changed while parsing).
                currentOnChange(EditorOps.mergeAdded(currentRules, r.added))
                message = EditorOps.bulkSummary(r)
            } catch (e: Exception) {
                message = "Couldn't read that list."
            } finally {
                parsing = false
            }
        }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val content = readText(context, uri)
                if (content == null) message = "Couldn't read that file (it must be text, at most 1 MB)." else bulk(content)
            }
        }
    }
    var shown by rememberSaveable { mutableStateOf(EditorOps.RULES_PAGE) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = kind == WebRuleKind.DOMAIN,
                onClick = { onInput(input.copy(kind = WebRuleKind.DOMAIN)) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("web_kind_domain"),
                icon = {},
            ) { Text("Website") }
            SegmentedButton(
                selected = kind == WebRuleKind.KEYWORD,
                onClick = { onInput(input.copy(kind = WebRuleKind.KEYWORD)) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("web_kind_keyword"),
                icon = {},
            ) { Text("Keyword") }
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                onInput(input.copy(text = it))
                message = null
            },
            label = { Text(if (kind == WebRuleKind.DOMAIN) "Website, e.g. reddit.com" else "Keyword, e.g. casino") },
            leadingIcon = {
                Icon(
                    if (kind ==
                        WebRuleKind.DOMAIN
                    ) {
                        Icons.Default.Language
                    } else {
                        Icons.Default.Search
                    },
                    contentDescription = null
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { (preview as? RulePreview.Adds)?.let { add(it.rule) } }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("web_input"),
        )
        if (kind == WebRuleKind.KEYWORD) {
            SwitchRow(
                title = "Match anywhere in the address",
                subtitle = "Also matches paths and searches. Only works where the browser shows the full address; some browsers " +
                    "show just the site name.",
                checked = anywhere,
                onChange = { onInput(input.copy(anywhere = it)) },
                tag = "web_anywhere",
            )
        }
        val previewText = when (preview) {
            RulePreview.Empty -> null
            is RulePreview.Adds -> preview.text
            is RulePreview.Choose -> "This address has a path. What should be ${if (verb == "Blocks") "blocked" else "allowed"}?"
            is RulePreview.Invalid -> preview.reason
        }
        if (previewText != null) {
            Text(
                previewText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (preview is RulePreview.Invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("web_preview")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (preview) {
            is RulePreview.Adds -> FilledTonalButton(
                onClick = { add(preview.rule) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("web_add")
            ) { Text("Add") }
            is RulePreview.Choose -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(
                    onClick = { add(preview.domain) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("web_choose_domain")
                ) { Text("The whole site (${preview.domain.value})") }
                FilledTonalButton(
                    onClick = { add(preview.keyword) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("web_choose_keyword")
                ) { Text("Pages containing “${preview.keyword.value}”") }
            }
            else -> Unit
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .testTag("web_message")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (rules.size > EditorOps.RULES_PAGE) {
            Text(
                "${rules.size} websites and keywords",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("web_rule_count"),
            )
        }
        // Not lazy (the editor is one scrolling column), so only a page of rows is composed at a time.
        rules.take(shown).forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("web_rule_${r.value}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.value, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        Format.rule(r, verb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onChange(rules - r) }, modifier = Modifier.testTag("web_remove_${r.value}")) {
                    Icon(Icons.Default.Close, contentDescription = "Remove ${r.value}")
                }
            }
        }
        if (rules.size > shown) {
            TextButton(
                onClick = { shown += 4 * EditorOps.RULES_PAGE },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("web_show_more"),
            ) { Text("Show more (${rules.size - shown} hidden)") }
        }
        if (parsing) Text("Adding…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("web_parsing"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { bulkOpen = true },
                enabled = !parsing,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("web_bulk_paste")
            ) { Text("Paste a list") }
            OutlinedButton(
                onClick = { csv.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/csv")) },
                enabled = !parsing,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("web_import_csv"),
            ) { Text("Import CSV") }
        }
    }
    if (bulkOpen) {
        BulkPasteDialog(onDismiss = { bulkOpen = false }, onAdd = {
            bulkOpen = false
            bulk(it)
        })
    }
}

@Composable
private fun BulkPasteDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    // Capped (here and so in the saved state): a pasted hosts file of several hundred KB would exceed the Binder
    // limit in onSaveInstanceState and crash the process (review round 2, adversarial 1).
    var text by rememberSaveable { mutableStateOf("") }
    var cut by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("bulk_dialog"),
        title = { Text("Paste a list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "One website or keyword per line. Lines with a comma use the first column.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        val capped = EditorOps.capPaste(it)
                        cut = capped != null
                        text = capped ?: it
                    },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("bulk_input"),
                )
                if (cut) {
                    Text(
                        "That list is too long to paste; only the lines at the start were kept. Use Import CSV for long lists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("bulk_cut"),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, modifier = Modifier.testTag("bulk_add")) { Text("Add all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
