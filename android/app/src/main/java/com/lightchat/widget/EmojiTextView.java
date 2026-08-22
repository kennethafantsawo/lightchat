package com.lightchat.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.emoji2.viewsintegration.EmojiTextViewHelper;

public class EmojiTextView extends TextView {
    private EmojiTextViewHelper mHelper;

    public EmojiTextView(Context context) {
        super(context);
        init();
    }

    public EmojiTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmojiTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mHelper = new EmojiTextViewHelper(this);
        setFilters(mHelper.getFilters(getFilters()));
        setTransformationMethod(mHelper.wrapTransformationMethod(getTransformationMethod()));
    }
}
