package com.futo.platformplayer.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.futo.platformplayer.*
import com.futo.platformplayer.logging.LogLevel
import com.futo.platformplayer.logging.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

class ExceptionActivity : AppCompatActivity() {
    private lateinit var _exText: TextView;
    private lateinit var _buttonShare: LinearLayout;
    private lateinit var _buttonSubmit: LinearLayout;
    private lateinit var _buttonRestart: LinearLayout;
    private lateinit var _buttonClose: LinearLayout;
    private var _file: File? = null;
    private var _submitted = false;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exception);
        setNavigationBarColorAndIcons();

        _exText = findViewById(R.id.ex_text);
        _buttonShare = findViewById(R.id.button_share);
        _buttonSubmit = findViewById(R.id.button_submit);
        _buttonRestart = findViewById(R.id.button_restart);
        _buttonClose = findViewById(R.id.button_close);

        val context = intent.getStringExtra(EXTRA_CONTEXT) ?: "Unknown Context";
        val stack = intent.getStringExtra(EXTRA_STACK) ?: "Something went wrong... missing stack trace?";

        val exceptionString = "Version information (version_name = ${BuildConfig.VERSION_NAME}, version_code = ${BuildConfig.VERSION_CODE}, flavor = ${BuildConfig.FLAVOR}, build_type = ${BuildConfig.BUILD_TYPE})\n" +
                "Device information (brand= ${Build.BRAND}, manufacturer = ${Build.MANUFACTURER}, device = ${Build.DEVICE}, version-sdk = ${Build.VERSION.SDK_INT}, version-os = ${Build.VERSION.BASE_OS})\n\n" +
                Logging.buildLogString(LogLevel.ERROR, TAG, "Uncaught exception (\"$context\"): $stack");
        try {
            val file = File(filesDir, "log.txt");
            if (!file.exists()) {
                file.createNewFile();
            }

            BufferedWriter(FileWriter(file, true)).use {
                it.appendLine(exceptionString);
            };

            _file = file
        } catch (e: Throwable) {
            //Ignored
        }

        _exText.text = stack;

        _buttonSubmit.setOnClickListener {
            submitFile();
        }

        _buttonShare.setOnClickListener {
            share(exceptionString);
        };

        _buttonRestart.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java));
        };
        _buttonClose.setOnClickListener {
            finish();
        };
    }

    private fun submitFile() {
        if (_submitted) {
            Toast.makeText(this, "Logs already submitted.", Toast.LENGTH_LONG).show();
            return;
        }

        val file = _file;
        if (file == null) {
            Toast.makeText(this, "No logs found.", Toast.LENGTH_LONG).show();
            return;
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var id: String? = null;

            try {
                id = Logging.submitLog(file);
            } catch (e: Throwable) {
                //Ignored
            }

            withContext(Dispatchers.Main) {
                if (id == null) {
                    try {
                        Toast.makeText(this@ExceptionActivity, "Failed automated share, share manually?", Toast.LENGTH_LONG).show();
                    } catch (e: Throwable) {
                        //Ignored
                    }
                } else {
                    _submitted = true;
                    file.delete();
                    Toast.makeText(this@ExceptionActivity, "Shared $id", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private fun share(exceptionString: String) {
        try {
            val i = Intent(Intent.ACTION_SEND);
            i.type = "text/plain";
            i.putExtra(Intent.EXTRA_EMAIL, arrayOf("grayjay@futo.org"));
            i.putExtra(Intent.EXTRA_SUBJECT, "Unhandled exception in VS");
            i.putExtra(Intent.EXTRA_TEXT, exceptionString);

            startActivity(Intent.createChooser(i, "Send exception to developers..."));
        } catch (e: Throwable) {
            //Ignored

        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_lighten, R.anim.slide_out_up)
    }

    companion object {
        private const val TAG = "ExceptionActivity";
        val EXTRA_CONTEXT = "CONTEXT";
        val EXTRA_STACK = "STACK";
    }
}