package app.keyholm.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

internal class SectionItem(
    val attachedToPrevious: Boolean,
    val content: @Composable (shape: Shape) -> Unit,
)

internal class SectionScope {
    val items = mutableListOf<SectionItem>()

    fun item(content: @Composable (shape: Shape) -> Unit) {
        items += SectionItem(attachedToPrevious = false, content)
    }

    /** Renders with no divider above and square corners between, so it reads as part of the row before it. */
    fun attachedItem(content: @Composable (shape: Shape) -> Unit) {
        items += SectionItem(attachedToPrevious = true, content)
    }
}
