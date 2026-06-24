package com.example.speedup.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val horizontalSpacing = dpToPx(8)
    private val verticalSpacing = dpToPx(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthLimit = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var width = 0
        var height = 0
        var rowWidth = 0
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (rowWidth + childWidth > widthLimit) {
                // Start a new row
                width = maxOf(width, rowWidth)
                height += rowHeight + verticalSpacing
                rowWidth = childWidth
                rowHeight = childHeight
            } else {
                // Add to current row
                rowWidth += childWidth + horizontalSpacing
                rowHeight = maxOf(rowHeight, childHeight)
            }
        }

        width = maxOf(width, rowWidth)
        height += rowHeight

        val finalWidth = resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec)
        val finalHeight = resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec)

        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val widthLimit = r - l - paddingLeft - paddingRight
        var currentLeft = paddingLeft
        var currentTop = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (currentLeft + childWidth > widthLimit + paddingLeft) {
                // Move to next row
                currentLeft = paddingLeft
                currentTop += rowHeight + verticalSpacing
                rowHeight = childHeight
            }

            child.layout(currentLeft, currentTop, currentLeft + childWidth, currentTop + childHeight)
            currentLeft += childWidth + horizontalSpacing
            rowHeight = maxOf(rowHeight, childHeight)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
