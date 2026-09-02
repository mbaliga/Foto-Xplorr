package com.fotoxplorr.app.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk

/**
 * The query, shown back as editable chips — the variables-editor idea from the owner's Quillbot
 * reference, applied to constraints instead of wordings.
 *
 * The value is that a search stops being an opaque string that either works or does not. Every
 * constraint the parser found is visible, and every one of them can be swapped or dropped in one
 * tap — so "why am I seeing nothing?" is answered by looking at the row rather than by retyping.
 *
 * Chips render only when the parser found something. A bare word typed into the field is one chip;
 * it is still worth showing, because its menu is where "only text IN the photo" lives, and that is
 * the option nobody would guess the syntax for.
 */
@Composable
fun SearchQueryChips(
    query: ParsedQuery,
    vocabulary: SearchVocabulary,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isEmpty) return

    // Which chip has its menu open, by index. -1 is none. Index rather than the term itself
    // because two identical words are two separate chips and must open separately.
    var openIndex by remember(query.raw) { mutableIntStateOf(-1) }

    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(query.terms.size) { index ->
            val term = query.terms[index]
            Box {
                Row(
                    modifier = Modifier
                        .background(CHIP_FILL, RoundedCornerShape(CHIP_RADIUS.dp))
                        .clickable { openIndex = index }
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = term.label(),
                        color = CHIP_INK,
                        style = TextStyle(
                            fontFamily = HyleGrotesk,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = "Change this part of the search",
                        tint = CHIP_INK.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = openIndex == index,
                    onDismissRequest = { openIndex = -1 },
                ) {
                    val alternatives = remember(query.raw, index, vocabulary) {
                        alternativesFor(query, index, vocabulary)
                    }
                    alternatives.forEach { suggestion ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    suggestion.label,
                                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
                                )
                            },
                            onClick = {
                                openIndex = -1
                                onQueryChange(suggestion.query)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * What sits under the last result: ways to widen a thin search, ways to cut a broad one.
 *
 * The owner asked for the bottom of a result list to offer expansion "in other dimensions", and
 * the direction is chosen by the result count — a search that found three things does not want
 * "narrow this further", and one that found four thousand does not want "try dropping a term".
 * Rendered as a footer inside the grid rather than as a floating panel so it is genuinely the
 * thing you meet when you run out of photos.
 */
@Composable
fun SearchExpansionFooter(
    query: ParsedQuery,
    resultCount: Int,
    vocabulary: SearchVocabulary,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = remember(query.raw, resultCount, vocabulary) {
        expansionSuggestions(query, resultCount, vocabulary)
    }
    if (suggestions.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
        Text(
            text = if (resultCount == 0) {
                "Nothing matched. Try:"
            } else if (resultCount <= 12) {
                "Only $resultCount. Try widening:"
            } else {
                "Narrow it down:"
            },
            color = FOOTER_LABEL,
            style = TextStyle(
                fontFamily = HyleGrotesk,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp,
            ),
        )
        Spacer(Modifier.padding(top = 10.dp))
        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQueryChange(suggestion.query) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A tiny leading rule tinted by direction: widening and narrowing are opposite
                // moves and should not look identical in a list you are scanning quickly.
                Box(
                    Modifier
                        .size(width = 3.dp, height = 16.dp)
                        .background(
                            when (suggestion.kind) {
                                SearchSuggestion.Kind.WIDEN -> WIDEN_MARK
                                SearchSuggestion.Kind.NARROW -> NARROW_MARK
                                SearchSuggestion.Kind.ALTERNATIVE -> ALTERNATIVE_MARK
                            },
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = suggestion.label,
                    color = FOOTER_INK,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp),
                )
            }
        }
    }
}

private const val CHIP_RADIUS = 10
private val CHIP_FILL = Color(0xFF1E1E22)
private val CHIP_INK = Color(0xFFECE8E4)
private val FOOTER_LABEL = Color(0xFF8E8E96)
private val FOOTER_INK = Color(0xFFECE8E4)
private val WIDEN_MARK = Color(0xFF8E7BFF)
private val NARROW_MARK = Color(0xFF6E97E8)
private val ALTERNATIVE_MARK = Color(0xFF5BBF7A)
