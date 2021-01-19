package com.mattrobertson.greek.reader.presentation.reader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.hilt.Assisted
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.*
import com.mattrobertson.greek.reader.R
import com.mattrobertson.greek.reader.data.DataBaseHelper
import com.mattrobertson.greek.reader.data.Recents
import com.mattrobertson.greek.reader.data.Settings
import com.mattrobertson.greek.reader.html.HtmlGenerator
import com.mattrobertson.greek.reader.html.readerHtmlHead
import com.mattrobertson.greek.reader.model.Book
import com.mattrobertson.greek.reader.model.GlossInfo
import com.mattrobertson.greek.reader.model.VerseRef
import com.mattrobertson.greek.reader.model.Word
import com.mattrobertson.greek.reader.presentation.util.ConcordanceWordSpan
import com.mattrobertson.greek.reader.presentation.util.ScreenState
import com.mattrobertson.greek.reader.presentation.util.SingleLiveEvent
import com.mattrobertson.greek.reader.util.AppConstants
import com.mattrobertson.greek.reader.util.getBookTitle
import com.mattrobertson.greek.reader.util.numChaptersInBook
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*

class ReaderViewModel @ViewModelInject constructor(
    private val htmlGenerator: HtmlGenerator,
    private val settings: Settings,
    @ApplicationContext private val applicationContext: Context,
    @Assisted private val savedState: SavedStateHandle
) : ViewModel() {

    private val loadJob = Job()
    private val loadScope = CoroutineScope(Dispatchers.Main + loadJob)

    private val _state = MutableLiveData<ScreenState>()
    val state: LiveData<ScreenState> = _state

    private val _title = MutableLiveData<String>()
    val title: LiveData<String> = _title

    private val _html = MutableLiveData<String>()
    val html: LiveData<String> = _html

    private val _glossInfo = MutableLiveData<GlossInfo?>()
    var glossInfo: LiveData<GlossInfo?> = _glossInfo

    private val _concordanceInfo = MutableLiveData<SpannableStringBuilder?>()
    var concordanceInfo: LiveData<SpannableStringBuilder?> = _concordanceInfo

    var concordanceItemSelected = SingleLiveEvent<VerseRef?>()
        private set

    var showConcordanceScreenForLex = SingleLiveEvent<String>()
        private set

    private lateinit var dbHelper: DataBaseHelper

    private var showAudioBtn = false

    private var currentRef = VerseRef(
        book = Book(savedState.get<Int>("book")
                ?: 5
        ),
        chapter = savedState.get<Int>("chapter")
            ?: 5,
        verse =  VerseRef.NO_VERSE
    )



    private val refBackstack = arrayListOf<VerseRef>()

    private var hasScrolled = false

    init {
        htmlGenerator.viewSettings.apply {
            showVerseNumbers = true
            showVersesNewLines = false
        }

        try {
            dbHelper = DataBaseHelper(applicationContext)
        } catch (e: Exception) {
            // TODO : log exception
        }

        goTo(currentRef)
    }

    override fun onCleared() {
        super.onCleared()
        loadJob.cancel()
    }

    @ExperimentalStdlibApi
    fun navigateBack(): Boolean {
        return if(refBackstack.isEmpty()) {
            false
        }
        else {
            val toRef = refBackstack.removeLast()
            goTo(VerseRef(toRef.book, toRef.chapter, toRef.verse))
            true
        }
    }

    fun nextChapter() {
        val isLastChapter = currentRef.chapter == numChaptersInBook(currentRef.book)
        val isLastBook = currentRef.book == Book.REVELATION

        val ref = when {
            isLastChapter -> {
                if (isLastBook)
                    currentRef.copy()
                else
                    VerseRef(
                        book = Book(currentRef.book.num + 1),
                        chapter = 1
                    )
            }
            else -> currentRef.copy(chapter = currentRef.chapter + 1)
        }

        goTo(ref)
    }

    fun prevChapter() {
        val isFirstChapter = currentRef.chapter == 1
        val isFirstBook = currentRef.book == Book.MATTHEW

        val ref = when {
            isFirstChapter -> {
                if (isFirstBook)
                    currentRef.copy()
                else {
                    val newBook = Book(currentRef.book.num - 1)

                    VerseRef(
                        book = newBook,
                        chapter = numChaptersInBook(newBook)
                    )
                }
            }
            else -> currentRef.copy(chapter = currentRef.chapter - 1)
        }

        goTo(ref)
    }

    fun goTo(ref: VerseRef) {
        currentRef = ref

        loadChapter(ref)
        addToRecents(ref)
    }

    private fun loadChapter(ref: VerseRef) = loadScope.launch {
        _state.value = ScreenState.LOADING

        hasScrolled = false
        _title.value = "${getBookTitle(ref.book)} ${ref.chapter}"

        val html = withContext(Dispatchers.IO) {
            htmlGenerator.getChapterHtml(ref)
        }

        _html.value = wrapHtml(html)
        _state.value = ScreenState.READY
    }

    private fun wrapHtml(html: String): String {
        val docStart = "<html>$readerHtmlHead<body>"
        val docEnd = "</body></html>"
        return docStart + html + docEnd
    }

    fun showGloss(word: Word) {
        val glossInfo = lookupGloss(word)

        viewModelScope.launch(Dispatchers.Main) {
            _glossInfo.value = glossInfo
        }

        glossInfo?.let {
            saveVocabWord(it)
        }
    }

    private fun lookupGloss(word: Word): GlossInfo? {
        val lex = word.lexicalForm
        val parsing = word.parsing.humanReadable

        if (lex.isBlank()) return null

        dbHelper.opendatabase()

        val db: SQLiteDatabase = dbHelper.readableDatabase

        var glossInfo: GlossInfo? = null

        val c = db.rawQuery("SELECT * FROM words WHERE lemma='$lex'", null)

        if (!c.moveToFirst()) {
            // Temp hack to find missing words
            val c2 = db.rawQuery("SELECT * FROM glosses WHERE gk='$lex'", null)
            if (c2.moveToFirst()) {
                val gloss = c2.getString(c2.getColumnIndex("gloss"))
                val freq = c2.getInt(c2.getColumnIndex("occ"))
                glossInfo = GlossInfo(lex = lex, gloss = gloss, parsing = parsing, frequency = freq)
            }
            c2.close()
        } else {
            val freq = c.getInt(c.getColumnIndex("freq"))
            val def = c.getString(c.getColumnIndex("def"))
            val gloss = c.getString(c.getColumnIndex("gloss"))
            val strDef = gloss ?: def
            glossInfo = GlossInfo(lex = lex, gloss = strDef, parsing = parsing, frequency = freq)
        }
        c.close()

        return glossInfo
    }

    fun showConcordance(word: Word) {
        viewModelScope.launch(Dispatchers.IO) {
            val concordInfo = lookupConcordance(word)

            viewModelScope.launch(Dispatchers.Main) {
                _concordanceInfo.value = concordInfo
            }
        }
    }

    private fun lookupConcordance(word: Word): SpannableStringBuilder? {
        val lex = word.lexicalForm

        if (lex.isBlank()) return null

        dbHelper.opendatabase()
        val db = dbHelper.readableDatabase
        val c = db.rawQuery("SELECT * FROM concordance WHERE lex='$lex'", null)

        var i = 0
        val size = c.count
        var strLine: String
        var totalLength = 0

        val sb = SpannableStringBuilder()
        var span: ConcordanceWordSpan

        val linkColor = ResourcesCompat.getColor(applicationContext.resources, R.color.accent, applicationContext.theme)

        while (c.moveToNext()) {
            val book = c.getInt(c.getColumnIndex("book"))
            val chapter = c.getInt(c.getColumnIndex("chapter"))
            val verse = c.getInt(c.getColumnIndex("verse"))

            i++

            strLine = "$i. ${AppConstants.abbrvs[book]} $chapter:$verse\n"

            sb.append(strLine)

            span = object : ConcordanceWordSpan(book, chapter, verse, linkColor) {
                override fun onClick(v: View) {
                    refBackstack.add(VerseRef(this@ReaderViewModel.currentRef.book, this@ReaderViewModel.currentRef.chapter))
                    goTo(VerseRef(Book(book), chapter, verse))
                }
            }

            sb.setSpan(span, totalLength, totalLength + strLine.length - 1, Spanned.SPAN_COMPOSING)

            totalLength += strLine.length

            if (i >= 10) {
                if (size > 10) {
                    val strMore = "..." + (size - i) + " more"
                    sb.append(strMore)
                    val spanMore: ClickableSpan = object : ClickableSpan() {
                        override fun onClick(v: View) {
                            showConcordanceScreenForLex.value = lex
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = linkColor
                            ds.isUnderlineText = false
                        }
                    }
                    sb.setSpan(spanMore, totalLength, totalLength + strMore.length, Spanned.SPAN_COMPOSING)
                }
                break
            }
        }

        c.close()
        return sb
    }

    private fun saveVocabWord(glossInfo: GlossInfo) {
        dbHelper.opendatabase()
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put("book", currentRef.book.num)
            put("chapter", currentRef.chapter)
            put("lex", glossInfo.lex)
            put("gloss", glossInfo.gloss)
            put("occ", glossInfo.frequency)
            put("added_by", DataBaseHelper.ADDED_BY_USER)
            put("date_added", System.currentTimeMillis())
            put("learned", 0)
        }

        db.insertWithOnConflict("vocab", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.close()
    }

    private fun addToRecents(ref: VerseRef) {
        Recents.add(ref)
        settings.saveRecents()
    }
}