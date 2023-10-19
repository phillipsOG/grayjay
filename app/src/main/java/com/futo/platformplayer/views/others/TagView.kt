package com.futo.platformplayer.views.others

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.futo.platformplayer.R
import com.futo.platformplayer.constructs.Event1

class TagView : LinearLayout {
    private val _root: FrameLayout;
    private val _textTag: TextView;
    private var _text: String = "";
    private var _value: Any? = null;

    var onClick = Event1<Pair<String, Any>>();

    constructor(context: Context, attrs: AttributeSet? = null) : super(context, attrs) {
        LayoutInflater.from(context).inflate(R.layout.view_tag, this, true);
        _root = findViewById(R.id.root);
        _textTag = findViewById(R.id.text_tag);
        _root.setOnClickListener { _value?.let { onClick.emit(Pair(_text, it)); }; }
    }

    fun setInfo(text: String, value: Any) {
        _text = text;
        _textTag.text = text;
        _value = value;
    }
}