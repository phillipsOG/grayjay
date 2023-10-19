package com.futo.platformplayer.views.fields

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.futo.platformplayer.R
import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import com.futo.platformplayer.constructs.Event2
import com.futo.platformplayer.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod
import kotlin.streams.asStream
import kotlin.streams.toList

class FieldForm : LinearLayout {

    private val _root : LinearLayout;

    val onChanged = Event2<IField, Any>();

    private var _fields : List<IField> = arrayListOf();

    constructor(context : Context, attrs : AttributeSet? = null) : super(context, attrs) {
        inflate(context, R.layout.field_form, this);
        _root = findViewById(R.id.field_form_root);
    }

    fun fromObject(scope: CoroutineScope, obj : Any, onLoaded: (()->Unit)? = null) {
        _root.removeAllViews();

        scope.launch(Dispatchers.Default) {
            val newFields = getFieldsFromObject(context, obj);

            withContext(Dispatchers.Main) {
                for (field in newFields) {
                    if (field !is View)
                        throw java.lang.IllegalStateException("Only views can be IFields");

                    _root.addView(field as View);
                    field.onChanged.subscribe { a1, a2 ->
                        onChanged.emit(a1, a2);
                    };
                }
                _fields = newFields;

                onLoaded?.invoke();
            }
        }
    }
    fun fromObject(obj : Any) {
        _root.removeAllViews();
        val newFields = getFieldsFromObject(context, obj);
        for(field in newFields) {
            if(field !is View)
                throw java.lang.IllegalStateException("Only views can be IFields");

            _root.addView(field as View);
            field.onChanged.subscribe { a1, a2 ->
                onChanged.emit(a1, a2);
            };
        }
        _fields = newFields;
    }
    fun fromPluginSettings(settings: List<SourcePluginConfig.Setting>, values: HashMap<String, String?>, groupTitle: String? = null, groupDescription: String? = null) {
        _root.removeAllViews();
        val newFields = getFieldsFromPluginSettings(context, settings, values);
        if (newFields.isEmpty()) {
            return;
        }

        if(groupTitle == null) {
            for(field in newFields) {
                if(field !is View)
                    throw java.lang.IllegalStateException("Only views can be IFields");
                field.onChanged.subscribe { field, value ->
                    onChanged.emit(field, value);
                }
                _root.addView(field as View);
            }
            _fields = newFields;
        } else {
            for(field in newFields) {
                field.onChanged.subscribe { field, value ->
                    onChanged.emit(field, value);
                }
            }
            val group = GroupField(context, groupTitle, groupDescription)
                .withFields(newFields);
            _root.addView(group as View);
        }
    }

    fun setObjectValues(){
        val fields = _fields;
        for (field in fields)
            field.setField();
    }

    fun findField(id: String) : IField? {
        for(field in _fields) {
            if(field?.descriptor?.id == id)
                return field;
            else if(field is GroupField)
            {
                val subField = field.findField(id);
                if(subField != null)
                    return subField;
            }
        }
        return null;
    }

    companion object
    {
        const val DROPDOWN = "dropdown";
        const val GROUP = "group";
        const val READONLYTEXT = "readonlytext";
        const val TOGGLE = "toggle";
        const val BUTTON = "button";

        private val _json = Json {};


        fun getFieldsFromPluginSettings(context: Context, settings: List<SourcePluginConfig.Setting>, values: HashMap<String, String?>): List<IField> {
            val fields = mutableListOf<IField>()

            for(setting in settings) {
                val field = when(setting.type.lowercase()) {
                    "boolean" -> {
                        val value = if(values.containsKey(setting.variableOrName)) values[setting.variableOrName] else setting.default;
                        val field = ToggleField(context).withValue(setting.name,
                            setting.description,
                            value == "true" || value == "1" || value == "True");
                        field.onChanged.subscribe { field, value ->
                            values[setting.variableOrName] = _json.encodeToString (value == 1 || value == true);
                        }
                        field;
                    }
                    else -> null;
                }

                if(field != null)
                    fields.add(field);
            }
            return fields;
        }

        fun getFieldsFromObject(context : Context, obj : Any) : List<IField> {
            val objFields = obj::class.declaredMemberProperties
                .asSequence()
                .asStream()
                .filter { it.hasAnnotation<FormField>() && it.javaField != null }
                .map { Pair<Field, FormField>(it.javaField!!, it.findAnnotation()!!) }
                .toList()

            val fields = mutableListOf<IField>();
            for(prop in objFields) {
                prop.first.isAccessible = true;

                val field = when(prop.second.type) {
                    GROUP -> GroupField(context).fromField(obj, prop.first, prop.second);
                    DROPDOWN -> DropdownField(context).fromField(obj, prop.first, prop.second);
                    TOGGLE -> ToggleField(context).fromField(obj, prop.first, prop.second);
                    READONLYTEXT -> ReadOnlyTextField(context).fromField(obj, prop.first, prop.second);
                    else -> throw java.lang.IllegalStateException("Unknown field type ${prop.second.type} for ${prop.second.title}")
                }
                fields.add(field as IField);
            }

            val objProps = obj::class.declaredMemberProperties
                .asSequence()
                .asStream()
                .filter { it.hasAnnotation<FormField>() && it.javaField == null && it.getter.javaMethod != null}
                .map { Pair<Method, FormField>(it.getter.javaMethod!!, it.findAnnotation()!!) }
                .toList();

            for(prop in objProps) {
                prop.first.isAccessible = true;

                val field = when(prop.second.type) {
                    READONLYTEXT -> ReadOnlyTextField(context).fromProp(obj, prop.first, prop.second);
                    else -> continue;
                }
                fields.add(field as IField);
            }

            //TODO: replace java.declaredMethods with declaredMemberFunctions instead of filtering out get/set
            val objMethods = obj::class.java.declaredMethods
                .asSequence()
                .asStream()
                .filter { it.getAnnotation(FormField::class.java) != null && !it.name.startsWith("get") && !it.name.startsWith("set") }
                .map { Pair<Method, FormField>(it, it.getAnnotation(FormField::class.java)) }
                .toList();

            for(meth in objMethods) {
                meth.first.isAccessible = true;

                val field = when(meth.second.type) {
                    BUTTON -> ButtonField(context).fromMethod(obj, meth.first);
                    else -> throw java.lang.IllegalStateException("Unknown method type ${meth.second.type} for ${meth.second.title}")
                }
                fields.add(field as IField);
            }

            return fields.sortedBy { it.descriptor?.order }.toList();
        }
    }
}