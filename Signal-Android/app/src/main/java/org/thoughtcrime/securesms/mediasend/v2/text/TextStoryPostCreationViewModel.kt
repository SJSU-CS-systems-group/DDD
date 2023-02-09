package org.thoughtcrime.securesms.mediasend.v2.text

import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.Subject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.fonts.TextFont
import org.thoughtcrime.securesms.fonts.TextToScript
import org.thoughtcrime.securesms.fonts.TypefaceCache
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.mediasend.v2.text.send.TextStoryPostSendRepository
import org.thoughtcrime.securesms.mediasend.v2.text.send.TextStoryPostSendResult
import org.thoughtcrime.securesms.util.livedata.Store

class TextStoryPostCreationViewModel(private val repository: TextStoryPostSendRepository, private val identityChangesSince: Long = System.currentTimeMillis()) : ViewModel() {

  private val store = Store(TextStoryPostCreationState())
  private val textFontSubject: Subject<TextFont> = BehaviorSubject.create()
  private val temporaryBodySubject: Subject<String> = BehaviorSubject.createDefault("")
  private val disposables = CompositeDisposable()

  private val internalTypeface = MutableLiveData<Typeface>()

  val state: LiveData<TextStoryPostCreationState> = store.stateLiveData
  val typeface: LiveData<Typeface> = internalTypeface

  init {
    textFontSubject.onNext(store.state.textFont)

    val scriptGuess = temporaryBodySubject.observeOn(Schedulers.io()).map { TextToScript.guessScript(it) }

    Observable.combineLatest(textFontSubject, scriptGuess, ::Pair)
      .observeOn(Schedulers.io())
      .distinctUntilChanged()
      .switchMapSingle { (textFont, script) -> TypefaceCache.get(ApplicationDependencies.getApplication(), textFont, script) }
      .subscribeOn(Schedulers.io())
      .subscribe {
        internalTypeface.postValue(it)
      }
  }

  fun compressToBlob(bitmap: Bitmap): Single<Uri> {
    return repository.compressToBlob(bitmap)
  }

  override fun onCleared() {
    disposables.clear()
  }

  fun saveToInstanceState(outState: Bundle) {
    outState.putParcelable(TEXT_STORY_INSTANCE_STATE, store.state)
  }

  fun restoreFromInstanceState(inState: Bundle) {
    if (inState.containsKey(TEXT_STORY_INSTANCE_STATE)) {
      val state: TextStoryPostCreationState = inState.getParcelable(TEXT_STORY_INSTANCE_STATE)!!
      textFontSubject.onNext(store.state.textFont)
      store.update { state }
    }
  }

  fun getBody(): CharSequence {
    return store.state.body
  }

  @ColorInt
  fun getTextColor(): Int {
    return store.state.textColor
  }

  fun setTextColor(@ColorInt textColor: Int) {
    store.update { it.copy(textColor = textColor) }
  }

  fun setBody(body: CharSequence) {
    store.update { it.copy(body = body) }
  }

  fun setAlignment(textAlignment: TextAlignment) {
    store.update { it.copy(textAlignment = textAlignment) }
  }

  fun setTextScale(scale: Int) {
    store.update { it.copy(textScale = scale) }
  }

  fun setTextColorStyle(textColorStyle: TextColorStyle) {
    store.update { it.copy(textColorStyle = textColorStyle) }
  }

  fun setTextFont(textFont: TextFont) {
    textFontSubject.onNext(textFont)
    store.update { it.copy(textFont = textFont) }
  }

  fun cycleBackgroundColor() {
    store.update { it.copy(backgroundColor = TextStoryBackgroundColors.cycleBackgroundColor(it.backgroundColor)) }
  }

  fun setLinkPreview(url: String?) {
    store.update { it.copy(linkPreviewUri = url) }
  }

  fun setTemporaryBody(temporaryBody: String) {
    temporaryBodySubject.onNext(temporaryBody)
  }

  fun send(contacts: Set<ContactSearchKey>, linkPreview: LinkPreview?): Single<TextStoryPostSendResult> {
    return repository.send(
      contacts,
      store.state,
      linkPreview,
      identityChangesSince
    )
  }

  class Factory(private val repository: TextStoryPostSendRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(TextStoryPostCreationViewModel(repository)) as T
    }
  }

  companion object {
    private val TAG = Log.tag(TextStoryPostCreationViewModel::class.java)
    private const val TEXT_STORY_INSTANCE_STATE = "text.story.instance.state"
  }
}
