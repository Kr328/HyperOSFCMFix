package com.github.kr328.simplefcmfix;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ChipFlowLayout extends ViewGroup {
    private final int spacing;

    public ChipFlowLayout(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        spacing = Math.round(6 * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int availableWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int lineWidth = 0;
        int lineHeight = 0;
        int widestLine = 0;
        int totalHeight = 0;
        int childState = 0;

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            childState = combineMeasuredStates(childState, child.getMeasuredState());
            final int childWidth = child.getMeasuredWidth();
            if (lineWidth > 0 && lineWidth + spacing + childWidth > availableWidth) {
                widestLine = Math.max(widestLine, lineWidth);
                totalHeight += lineHeight + spacing;
                lineWidth = 0;
                lineHeight = 0;
            }
            lineWidth += (lineWidth == 0 ? 0 : spacing) + childWidth;
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
        widestLine = Math.max(widestLine, lineWidth);
        totalHeight += lineHeight;

        setMeasuredDimension(
                resolveSizeAndState(widestLine + getPaddingLeft() + getPaddingRight(),
                        widthMeasureSpec, childState),
                resolveSizeAndState(totalHeight + getPaddingTop() + getPaddingBottom(),
                        heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(final boolean changed, final int left, final int top,
                            final int right, final int bottom) {
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int lineWidth = 0;
        int lineHeight = 0;
        int y = getPaddingTop();

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            if (lineWidth > 0 && lineWidth + spacing + childWidth > availableWidth) {
                y += lineHeight + spacing;
                lineWidth = 0;
                lineHeight = 0;
            }
            final int offset = lineWidth == 0 ? 0 : lineWidth + spacing;
            final int x = rtl
                    ? getWidth() - getPaddingRight() - offset - childWidth
                    : getPaddingLeft() + offset;
            child.layout(x, y, x + childWidth, y + childHeight);
            lineWidth = offset + childWidth;
            lineHeight = Math.max(lineHeight, childHeight);
        }
    }
}
