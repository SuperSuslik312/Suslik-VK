package dev.ragnarok.fenrir.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.SparseArray
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.NonNull
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.squareup.picasso3.Callback
import com.squareup.picasso3.Transformation
import dev.ragnarok.fenrir.Constants
import dev.ragnarok.fenrir.Extensions.Companion.fromIOToMain
import dev.ragnarok.fenrir.Extra
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.activity.ActivityFeatures
import dev.ragnarok.fenrir.activity.ActivityUtils
import dev.ragnarok.fenrir.activity.SendAttachmentsActivity
import dev.ragnarok.fenrir.fragment.base.BaseMvpFragment
import dev.ragnarok.fenrir.link.LinkHelper
import dev.ragnarok.fenrir.media.gif.IGifPlayer
import dev.ragnarok.fenrir.model.PhotoSize
import dev.ragnarok.fenrir.model.Story
import dev.ragnarok.fenrir.module.FenrirNative
import dev.ragnarok.fenrir.module.parcel.ParcelNative
import dev.ragnarok.fenrir.mvp.core.IPresenterFactory
import dev.ragnarok.fenrir.mvp.presenter.StoryPagerPresenter
import dev.ragnarok.fenrir.mvp.view.IStoryPagerView
import dev.ragnarok.fenrir.picasso.PicassoInstance
import dev.ragnarok.fenrir.place.PlaceFactory
import dev.ragnarok.fenrir.settings.CurrentTheme
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.util.*
import dev.ragnarok.fenrir.util.CustomToast.Companion.CreateCustomToast
import dev.ragnarok.fenrir.util.Objects
import dev.ragnarok.fenrir.util.Utils.nonEmpty
import dev.ragnarok.fenrir.view.AlternativeAspectRatioFrameLayout
import dev.ragnarok.fenrir.view.CircleCounterButton
import dev.ragnarok.fenrir.view.TouchImageView
import dev.ragnarok.fenrir.view.natives.rlottie.RLottieImageView
import dev.ragnarok.fenrir.view.pager.WeakPicassoLoadCallback
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.TimeUnit


class StoryPagerFragment : BaseMvpFragment<StoryPagerPresenter, IStoryPagerView>(),
    IStoryPagerView {
    private val mHolderSparseArray = SparseArray<WeakReference<MultiHolder>>()
    private var mViewPager: ViewPager2? = null
    private var mToolbar: Toolbar? = null
    private var mAvatar: ImageView? = null
    private var mExp: TextView? = null
    private var transformation: Transformation? = null
    private var mDownload: CircleCounterButton? = null
    private var mShare: CircleCounterButton? = null
    private var mLink: CircleCounterButton? = null
    private var mFullscreen = false
    private var helpDisposable = Disposable.disposed()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            mFullscreen = savedInstanceState.getBoolean("mFullscreen")
        }
        transformation = CurrentTheme.createTransformationForAvatar()
    }

    private val requestWritePermission = AppPerms.requestPermissions(
        this,
        arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    ) {
        presenter?.fireWritePermissionResolved()
    }

    override fun requestWriteExternalStoragePermission() {
        requestWritePermission.launch()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_story_pager, container, false)
        mToolbar = root.findViewById(R.id.toolbar)
        (requireActivity() as AppCompatActivity).setSupportActionBar(mToolbar)
        mAvatar = root.findViewById(R.id.toolbar_avatar)
        mViewPager = root.findViewById(R.id.view_pager)
        mViewPager?.offscreenPageLimit = 1
        mViewPager?.setPageTransformer(
            Utils.createPageTransform(
                Settings.get().main().viewpager_page_transform
            )
        )
        mExp = root.findViewById(R.id.item_story_expires)
        val mHelper = root.findViewById<RLottieImageView?>(R.id.swipe_helper)
        if (HelperSimple.needHelp(HelperSimple.STORY_HELPER, 2)) {
            mHelper?.visibility = View.VISIBLE
            mHelper?.fromRes(
                R.raw.story_guide_hand_swipe,
                Utils.dp(500F),
                Utils.dp(500F),
                intArrayOf(0x333333, CurrentTheme.getColorSecondary(requireActivity()))
            )
            mHelper?.playAnimation()
            helpDisposable = Completable.create {
                it.onComplete()
            }.delay(5, TimeUnit.SECONDS).fromIOToMain().subscribe({
                mHelper?.clearAnimationDrawable()
                mHelper?.visibility = View.GONE
            }, RxUtils.ignore())
        } else {
            mHelper?.visibility = View.GONE
        }
        mViewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                presenter?.firePageSelected(position)
            }
        })
        mDownload = root.findViewById(R.id.button_download)
        mShare = root.findViewById(R.id.button_share)
        mShare?.setOnClickListener { presenter?.fireShareButtonClick() }
        mDownload?.setOnClickListener { presenter?.fireDownloadButtonClick() }
        mLink = root.findViewById(R.id.button_link)
        resolveFullscreenViews()
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("mFullscreen", mFullscreen)
    }

    override fun onResume() {
        super.onResume()
        ActivityFeatures.Builder()
            .begin()
            .setHideNavigationMenu(true)
            .setBarsColored(false, false)
            .build()
            .apply(requireActivity())
    }

    private fun toggleFullscreen() {
        mFullscreen = !mFullscreen
        resolveFullscreenViews()
    }

    private fun resolveFullscreenViews() {
        mToolbar?.visibility = if (mFullscreen) View.GONE else View.VISIBLE
        mDownload?.visibility = if (mFullscreen) View.GONE else View.VISIBLE
        mShare?.visibility = if (mFullscreen) View.GONE else View.VISIBLE
    }

    override fun getPresenterFactory(saveInstanceState: Bundle?): IPresenterFactory<StoryPagerPresenter> =
        object : IPresenterFactory<StoryPagerPresenter> {
            override fun create(): StoryPagerPresenter {
                val aid = requireArguments().getInt(Extra.ACCOUNT_ID)
                val index = requireArguments().getInt(Extra.INDEX)
                val stories: ArrayList<Story> = if (FenrirNative.isNativeLoaded() && Settings.get()
                        .other().isNative_parcel_story
                ) ParcelNative.loadParcelableArrayList(
                    requireArguments().getLong(
                        Extra.STORY
                    ), Story.NativeCreator
                ) else
                    requireArguments().getParcelableArrayList(Extra.STORY)!!
                if (FenrirNative.isNativeLoaded() && Settings.get()
                        .other().isNative_parcel_story
                ) {
                    requireArguments().putLong(Extra.STORY, 0)
                }
                return StoryPagerPresenter(
                    aid,
                    stories,
                    index,
                    requireActivity(),
                    saveInstanceState
                )
            }
        }

    override fun displayData(pageCount: Int, selectedIndex: Int) {
        val adapter = Adapter(pageCount)
        mViewPager?.adapter = adapter
        mViewPager?.setCurrentItem(selectedIndex, false)
    }

    override fun setAspectRatioAt(position: Int, w: Int, h: Int) {
        findByPosition(position)?.setAspectRatio(w, h)
    }

    override fun setPreparingProgressVisible(position: Int, preparing: Boolean) {
        for (i in 0 until mHolderSparseArray.size()) {
            val key = mHolderSparseArray.keyAt(i)
            val holder = findByPosition(key)
            val isCurrent = position == key
            val progressVisible = isCurrent && preparing
            holder?.setProgressVisible(progressVisible)
            holder?.setSurfaceVisible(if (isCurrent && !preparing) View.VISIBLE else View.GONE)
        }
    }

    override fun attachDisplayToPlayer(adapterPosition: Int, gifPlayer: IGifPlayer?) {
        val holder = findByPosition(adapterPosition)
        if (holder?.isSurfaceReady == true) {
            gifPlayer?.setDisplay(holder.mSurfaceHolder)
        }
    }

    override fun setToolbarTitle(@StringRes titleRes: Int, vararg params: Any) {
        ActivityUtils.supportToolbarFor(this)?.title = getString(titleRes, *params)
    }

    override fun setToolbarSubtitle(story: Story, account_id: Int) {
        ActivityUtils.supportToolbarFor(this)?.subtitle = story.owner.fullName
        mAvatar?.setOnClickListener {
            PlaceFactory.getOwnerWallPlace(account_id, story.owner)
                .tryOpenWith(requireActivity())
        }
        mAvatar?.let {
            ViewUtils.displayAvatar(
                it,
                transformation,
                story.owner.maxSquareAvatar,
                Constants.PICASSO_TAG
            )
        }
        if (story.expires <= 0) mExp?.visibility = View.GONE else {
            mExp?.visibility = View.VISIBLE
            val exp = (story.expires - Calendar.getInstance().time.time / 1000) / 3600
            mExp?.text = getString(
                R.string.expires,
                exp.toString(),
                getString(
                    Utils.declOfNum(
                        exp,
                        intArrayOf(R.string.hour, R.string.hour_sec, R.string.hours)
                    )
                )
            )
        }
        if (Utils.isEmpty(story.target_url)) {
            mLink?.visibility = View.GONE
        } else {
            mLink?.visibility = View.VISIBLE
            mLink?.setOnClickListener {
                LinkHelper.openUrl(
                    requireActivity(),
                    account_id,
                    story.target_url
                )
            }
        }
    }

    override fun onShare(story: Story, account_id: Int) {
        SendAttachmentsActivity.startForSendAttachments(requireActivity(), account_id, story)
    }

    override fun configHolder(
        adapterPosition: Int,
        progress: Boolean,
        aspectRatioW: Int,
        aspectRatioH: Int
    ) {
        val holder = findByPosition(adapterPosition)
        holder?.setProgressVisible(progress)
        holder?.setAspectRatio(aspectRatioW, aspectRatioH)
        holder?.setSurfaceVisible(if (progress) View.GONE else View.VISIBLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        helpDisposable.dispose()
    }

    override fun onNext() {
        mViewPager?.let {
            it.adapter?.let { so ->
                if (so.itemCount > it.currentItem + 1) {
                    it.setCurrentItem(it.currentItem + 1, true)
                }
            }
        }
    }

    private fun fireHolderCreate(holder: MultiHolder) {
        presenter?.fireHolderCreate(holder.bindingAdapterPosition)
    }

    private fun findByPosition(position: Int): MultiHolder? {
        val weak = mHolderSparseArray[position]
        return if (Objects.isNull(weak)) null else weak.get()
    }

    open class MultiHolder internal constructor(rootView: View) :
        RecyclerView.ViewHolder(rootView) {
        lateinit var mSurfaceHolder: SurfaceHolder
        open val isSurfaceReady: Boolean
            get() = false

        open fun setProgressVisible(visible: Boolean) {}
        open fun setAspectRatio(w: Int, h: Int) {}
        open fun setSurfaceVisible(Vis: Int) {}
        open fun bindTo(story: Story) {}
    }

    private inner class Holder(rootView: View) : MultiHolder(rootView), SurfaceHolder.Callback {
        val mSurfaceView: SurfaceView = rootView.findViewById(R.id.surface_view)
        val mProgressBar: RLottieImageView
        val mAspectRatioLayout: AlternativeAspectRatioFrameLayout
        override var isSurfaceReady = false
        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceReady = true
            presenter?.fireSurfaceCreated(bindingAdapterPosition)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            isSurfaceReady = false
        }

        override fun setProgressVisible(visible: Boolean) {
            mProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) {
                mProgressBar.fromRes(
                    R.raw.loading,
                    Utils.dp(80F),
                    Utils.dp(80F),
                    intArrayOf(
                        0xffffff,
                        CurrentTheme.getColorPrimary(requireActivity()),
                        0x000000,
                        CurrentTheme.getColorSecondary(requireActivity())
                    )
                )
                mProgressBar.playAnimation()
            } else {
                mProgressBar.stopAnimation()
            }
        }

        override fun setAspectRatio(w: Int, h: Int) {
            mAspectRatioLayout.setAspectRatio(w, h)
        }

        override fun setSurfaceVisible(Vis: Int) {
            mSurfaceView.visibility = Vis
        }

        init {
            mSurfaceHolder = mSurfaceView.holder
            mSurfaceHolder.addCallback(this)
            mAspectRatioLayout = rootView.findViewById(R.id.aspect_ratio_layout)
            mProgressBar = rootView.findViewById(R.id.preparing_progress_bar)
            mSurfaceView.setOnClickListener { toggleFullscreen() }
        }
    }

    private inner class PhotoViewHolder(view: View) : MultiHolder(view), Callback {
        val reload: FloatingActionButton
        private val mPicassoLoadCallback: WeakPicassoLoadCallback
        val photo: TouchImageView
        val progress: RLottieImageView
        private var mLoadingNow = false
        override fun bindTo(@NonNull story: Story) {
            photo.resetZoom()
            if (story.isIs_expired) {
                CreateCustomToast(requireActivity()).showToastError(R.string.is_expired)
                mLoadingNow = false
                resolveProgressVisibility()
                return
            }
            if (story.photo == null) return
            val url = story.photo.getUrlForSize(PhotoSize.W, true)
            reload.setOnClickListener {
                reload.visibility = View.INVISIBLE
                if (nonEmpty(url)) {
                    loadImage(url)
                } else PicassoInstance.with().cancelRequest(photo)
            }
            if (nonEmpty(url)) {
                loadImage(url)
            } else {
                PicassoInstance.with().cancelRequest(photo)
                CreateCustomToast(requireActivity()).showToast(R.string.empty_url)
            }
        }

        private fun resolveProgressVisibility() {
            progress.visibility = if (mLoadingNow) View.VISIBLE else View.GONE
            if (mLoadingNow) {
                progress.fromRes(
                    R.raw.loading,
                    Utils.dp(80F),
                    Utils.dp(80F),
                    intArrayOf(
                        0xffffff,
                        CurrentTheme.getColorPrimary(requireActivity()),
                        0x000000,
                        CurrentTheme.getColorSecondary(requireActivity())
                    )
                )
                progress.playAnimation()
            } else {
                progress.stopAnimation()
            }
        }

        private fun loadImage(@NonNull url: String?) {
            mLoadingNow = true
            resolveProgressVisibility()
            PicassoInstance.with()
                .load(url)
                .into(photo, mPicassoLoadCallback)
        }

        @IdRes
        private fun idOfImageView(): Int {
            return R.id.image_view
        }

        @IdRes
        private fun idOfProgressBar(): Int {
            return R.id.progress_bar
        }

        override fun onSuccess() {
            mLoadingNow = false
            resolveProgressVisibility()
            reload.visibility = View.INVISIBLE
        }

        override fun onError(t: Throwable) {
            mLoadingNow = false
            resolveProgressVisibility()
            reload.visibility = View.VISIBLE
        }

        init {
            photo = view.findViewById(idOfImageView())
            photo.maxZoom = 8f
            photo.doubleTapScale = 2f
            photo.doubleTapMaxZoom = 4f
            progress = view.findViewById(idOfProgressBar())
            reload = view.findViewById(R.id.goto_button)
            mPicassoLoadCallback = WeakPicassoLoadCallback(this)
            photo.setOnClickListener { toggleFullscreen() }
        }
    }

    private inner class Adapter(val mPageCount: Int) : RecyclerView.Adapter<MultiHolder>() {
        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(container: ViewGroup, viewType: Int): MultiHolder {
            if (viewType == 0) return Holder(
                LayoutInflater.from(container.context)
                    .inflate(R.layout.content_story_page, container, false)
            )
            val ret = PhotoViewHolder(
                LayoutInflater.from(container.context)
                    .inflate(R.layout.content_story_photo_page, container, false)
            )
            ret.photo.setOnTouchListener { view: View, event: MotionEvent ->
                if (event.pointerCount >= 2 || view.canScrollVertically(1) && view.canScrollVertically(
                        -1
                    )
                ) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            container.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener false
                        }
                        MotionEvent.ACTION_UP -> {
                            container.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener true
                        }
                    }
                }
                true
            }
            return ret
        }

        override fun onBindViewHolder(holder: MultiHolder, position: Int) {
            presenter?.let {
                if (!it.isStoryIsVideo(position)) holder.bindTo(it.getStory(position))
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (presenter?.isStoryIsVideo(position) == true) 0 else 1
        }

        override fun getItemCount(): Int {
            return mPageCount
        }

        override fun onViewDetachedFromWindow(holder: MultiHolder) {
            super.onViewDetachedFromWindow(holder)
            mHolderSparseArray.remove(holder.bindingAdapterPosition)
        }

        override fun onViewAttachedToWindow(holder: MultiHolder) {
            super.onViewAttachedToWindow(holder)
            mHolderSparseArray.put(holder.bindingAdapterPosition, WeakReference(holder))
            fireHolderCreate(holder)
        }

        init {
            mHolderSparseArray.clear()
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(args: Bundle?): StoryPagerFragment {
            val fragment = StoryPagerFragment()
            fragment.arguments = args
            return fragment
        }

        @JvmStatic
        fun buildArgs(aid: Int, stories: ArrayList<Story?>, index: Int): Bundle {
            val args = Bundle()
            args.putInt(Extra.ACCOUNT_ID, aid)
            args.putInt(Extra.INDEX, index)
            if (FenrirNative.isNativeLoaded() && Settings.get().other().isNative_parcel_story) {
                args.putLong(Extra.STORY, ParcelNative.createParcelableList(stories))
            } else {
                args.putParcelableArrayList(Extra.STORY, stories)
            }
            return args
        }
    }
}
