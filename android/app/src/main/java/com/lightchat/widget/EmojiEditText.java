package com.lightchat.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

import androidx.emoji2.viewsintegration.EmojiTextViewHelper;

public class EmojiEditText extends EditText {
    private EmojiTextViewHelper mHelper;

    public EmojiEditText(Context context) {
        super(context);
        init();
    }

    public EmojiEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmojiEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mHelper = new EmojiTextViewHelper(this);
        setFilters(mHelper.getFilters(getFilters()));
        setTransformationMethod(mHelper.wrapTransformationMethod(getTransformationMethod()));
    }
}
