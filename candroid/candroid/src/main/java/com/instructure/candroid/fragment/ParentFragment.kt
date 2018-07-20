/*
 * Copyright (C) 2016 - present Instructure, Inc.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.instructure.candroid.fragment

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.support.annotation.MenuRes
import android.support.design.widget.Snackbar
import android.support.v4.app.DialogFragment
import android.support.v4.app.Fragment
import android.support.v4.app.LoaderManager
import android.support.v4.content.Loader
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.*
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import com.instructure.candroid.R
import com.instructure.candroid.activity.VideoViewActivity
import com.instructure.candroid.adapter.ExpandableRecyclerAdapter
import com.instructure.candroid.decorations.DividerItemDecoration
import com.instructure.candroid.decorations.ExpandableGridSpacingDecorator
import com.instructure.candroid.decorations.GridSpacingDecorator
import com.instructure.candroid.interfaces.ConfigureRecyclerView
import com.instructure.candroid.util.FileUtils
import com.instructure.candroid.util.LoggingUtility
import com.instructure.candroid.util.StudentPrefs
import com.instructure.canvasapi2.models.*
import com.instructure.canvasapi2.utils.APIHelper
import com.instructure.canvasapi2.utils.Logger
import com.instructure.canvasapi2.utils.NetworkUtils
import com.instructure.interactions.FragmentInteractions
import com.instructure.interactions.Navigation
import com.instructure.interactions.bookmarks.Bookmarkable
import com.instructure.pandarecycler.BaseRecyclerAdapter
import com.instructure.pandarecycler.PaginatedRecyclerAdapter
import com.instructure.pandarecycler.PandaRecyclerView
import com.instructure.pandarecycler.interfaces.EmptyViewInterface
import com.instructure.pandarecycler.util.Types
import com.instructure.pandautils.loaders.OpenMediaAsyncTaskLoader
import com.instructure.pandautils.utils.Const
import com.instructure.pandautils.utils.LoaderUtils
import com.instructure.pandautils.utils.PermissionUtils
import com.instructure.pandautils.utils.hasPermissions
import java.io.File
import java.io.FileOutputStream

abstract class ParentFragment : DialogFragment(), ConfigureRecyclerView, FragmentInteractions {

    private var openMediaBundle: Bundle? = null
    private var openMediaCallbacks: LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>? = null

    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    private val shouldUpdateTitle = true
    private var loadedMedia: OpenMediaAsyncTaskLoader.LoadedMedia? = null
    private var spacingDecoration: RecyclerView.ItemDecoration? = null

    override val navigation: Navigation?
        get() = if (activity is Navigation) {
            activity as Navigation
        } else null

    // region OpenMediaAsyncTaskLoader

    private val loaderCallbacks: LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>?
        get() {
            if (openMediaCallbacks == null) {
                openMediaCallbacks = object : LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia> {
                    override fun onCreateLoader(id: Int, args: Bundle): Loader<OpenMediaAsyncTaskLoader.LoadedMedia> {
                        onMediaLoadingStarted()
                        return OpenMediaAsyncTaskLoader(context, args)
                    }

                    override fun onLoadFinished(loader: Loader<OpenMediaAsyncTaskLoader.LoadedMedia>, loadedMedia: OpenMediaAsyncTaskLoader.LoadedMedia) {
                        try {
                            if (loadedMedia.isError) {
                                if (loadedMedia.errorType == OpenMediaAsyncTaskLoader.ERROR_TYPE.NO_APPS) {
                                    this@ParentFragment.loadedMedia = loadedMedia
                                    Snackbar.make(view!!, getString(R.string.noAppsShort), Snackbar.LENGTH_LONG)
                                            .setAction(getString(R.string.download), snackbarClickListener)
                                            .setActionTextColor(Color.WHITE)
                                            .show()
                                } else {
                                    if (activity != null) {
                                        Toast.makeText(activity, activity.resources.getString(loadedMedia.errorMessage), Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else if (loadedMedia.isHtmlFile) {
                                InternalWebviewFragment.loadInternalWebView(activity, InternalWebviewFragment.makeRoute(loadedMedia.bundle))
                            } else if (loadedMedia.intent != null && context != null) {
                                // Show pdf with PSPDFkit
                                if (loadedMedia.intent.type!!.contains("pdf") && !loadedMedia.isUseOutsideApps) {
                                    val uri = loadedMedia.intent.data
                                    FileUtils.showPdfDocument(uri, loadedMedia, context)
                                } else if (loadedMedia.intent.type == "video/mp4") {
                                    activity?.startActivity(VideoViewActivity.createIntent(context, loadedMedia.intent.dataString))
                                } else {
                                    activity?.startActivity(loadedMedia.intent)
                                }
                            }
                        } catch (e: ActivityNotFoundException) {
                            if (activity != null) {
                                Toast.makeText(activity, R.string.noApps, Toast.LENGTH_LONG).show()
                            }
                        }

                        openMediaBundle = null // Set to null, otherwise the progressDialog will appear again
                        onMediaLoadingComplete()
                    }

                    override fun onLoaderReset(loader: Loader<OpenMediaAsyncTaskLoader.LoadedMedia>) {}
                }
            }
            return openMediaCallbacks
        }

    open fun onMediaLoadingStarted(){}
    open fun onMediaLoadingComplete(){}

    var snackbarClickListener: View.OnClickListener = View.OnClickListener {
        try {
            downloadFileToDownloadDir(context, loadedMedia!!.intent.data!!.path)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, R.string.errorOccurred, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        // First saving my state, so the bundle wont be empty
        LoaderUtils.saveLoaderBundle(outState, openMediaBundle, Const.OPEN_MEDIA_LOADER_BUNDLE)

        outState?.putString("WORKAROUND_FOR_BUG_19917_KEY", "WORKAROUND_FOR_BUG_19917_VALUE")
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LoggingUtility.Log(activity, Log.DEBUG, Logger.getFragmentName(this) + " --> On Create")
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        LoggingUtility.Log(activity, Log.DEBUG, Logger.getFragmentName(this) + " --> On Create View")
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        LoggingUtility.Log(activity, Log.DEBUG, Logger.getFragmentName(this) + " --> On Activity Created")

        LoaderUtils.restoreLoaderFromBundle<LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>>(activity.supportLoaderManager, savedInstanceState, loaderCallbacks, R.id.openMediaLoaderID, Const.OPEN_MEDIA_LOADER_BUNDLE)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        setHasOptionsMenu(true)
    }

    override fun onDetach() {
        // This could go wrong, but we don't want to crash the app since we are just dismissing the soft keyboard
        try {
            val view = activity.currentFocus
            if (view != null) {
                val inputManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.hideSoftInputFromWindow(view.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
            }
        } catch (e: Exception) {
            LoggingUtility.Log(activity, Log.DEBUG, "An exception was thrown while trying to dismiss the keyboard: " + e.message)
        }

        // Very important fix for the support library and child fragments.
        try {
            val childFragmentManager = Fragment::class.java.getDeclaredField("mChildFragmentManager")
            childFragmentManager.isAccessible = true
            childFragmentManager.set(this, null)
        } catch (e: NoSuchFieldException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }

        super.onDetach()
    }

    override fun onStart() {
        super.onStart()
        LoggingUtility.Log(activity, Log.DEBUG, Logger.getFragmentName(this) + " --> On Start")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window!!.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        return dialog
    }

    override fun onDestroyView() {
        if (retainInstance)
            dialog?.setDismissMessage(null)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        LoggingUtility.Log(activity, Log.DEBUG, Logger.getFragmentName(this) + " --> On Resume")
    }

    override fun onPause() {
        super.onPause()
        LoggingUtility.Log(activity, Log.DEBUG, Logger.getFragmentName(this) + " --> On Pause.")
    }

    open fun handleBackPressed(): Boolean = false

    //region Toolbar & Menus

    /**
     * General setup method for toolbar menu items
     * All menu item selections are returned to the onToolbarMenuItemClick() function
     * @param toolbar a toolbar
     */
    fun setupToolbarMenu(toolbar: Toolbar) {
        addBookmarkMenuIfAllowed(toolbar)
        addOnMenuItemClickListener(toolbar)
    }

    /**
     * General setup method for toolbar menu items
     * All menu item selections are returned to the onToolbarMenuItemClick() function
     * @param toolbar a toolbar
     * @param menu xml menu resource id, R.menu.matthew_rice_is_great
     */
    fun setupToolbarMenu(toolbar: Toolbar, @MenuRes menu: Int) {
        toolbar.menu.clear()
        addBookmarkMenuIfAllowed(toolbar)
        toolbar.inflateMenu(menu)
        addOnMenuItemClickListener(toolbar)
    }

    private fun addBookmarkMenuIfAllowed(toolbar: Toolbar) {
        if (this is Bookmarkable && this.bookmark.canBookmark && toolbar.menu.findItem(R.id.bookmark) == null) {
            toolbar.inflateMenu(R.menu.bookmark_menu)
        }
    }

    private fun addOnMenuItemClickListener(toolbar: Toolbar) {
        toolbar.setOnMenuItemClickListener { item -> onOptionsItemSelected(item) }
    }

    /**
     * Override to handle toolbar menu item clicks
     * Super() should be called most if not all of the time.
     * @param item a menu item
     * @return true if the menu item click was handled
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.bookmark) {
            if (APIHelper.hasNetworkConnection()) {
                navigation?.addBookmark()
            } else {
                Toast.makeText(context, context.getString(R.string.notAvailableOffline), Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return false
    }

    // Fragment-ception fix:
    // Some fragments (currently our AssigmentFragment) have children fragments.
    // In the module progression view pager these child fragments don't get
    // destroyed when the root fragment gets destroyed. Override this function
    // in the appropriate activity to remove child fragments.  For example, in
    // the module progression class we call this function when onDestroyItem
    // is called and it is implemented in the AssignmentFragment class.
    fun removeChildFragments() {}

    override fun startActivity(intent: Intent) {
        if (context == null) {
            return
        }
        super.startActivity(intent)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        if (context == null) {
            return
        }
        super.startActivityForResult(intent, requestCode)
    }

    /**
     * Will try to save data if some exits
     * Intended to be used with @dataLossResume()
     * @param editText
     * @param preferenceConstant the constant the fragment is using to store data
     */
    fun dataLossPause(editText: EditText?, preferenceConstant: String) {
        editText?.let {
            if (!TextUtils.isEmpty(it.text)) {
                // Data exists in message editText so we want to save it.
                StudentPrefs.putString(preferenceConstant, it.text.toString())
            }
        }
    }

    /**
     * Restores data that may have been lost by navigating
     * @param editText
     * @param preferenceConstant the constant the fragment is using to store data
     * @return if the data was restored
     */
    fun dataLossResume(editText: EditText?, preferenceConstant: String): Boolean {
        //If we have no text in our editText
        if (editText != null && TextUtils.isEmpty(editText.text)) {
            //and we have text stored, we can restore that text
            val messageText = StudentPrefs.getString(preferenceConstant, "")
            if (!TextUtils.isEmpty(messageText)) {
                editText.setText(messageText)
                return true
            }
        }
        return false
    }

    /**
     * Will remove any data for a given constant
     * @param preferenceConstant
     */
    fun dataLossDeleteStoredData(preferenceConstant: String) = StudentPrefs.remove(preferenceConstant)

    /**
     * A text watcher that will remove any data stored when the user has removed all text
     * @param editText
     * @param preferenceConstant the constant the fragment is using to store data
     */
    fun dataLossAddTextWatcher(editText: EditText?, preferenceConstant: String) {
        editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (s.toString().isEmpty()) {
                    dataLossDeleteStoredData(preferenceConstant)
                }
            }
        })
    }
    // region RecyclerView Methods

    // The paramName is used to specify which param should be selected when the list loads for the first time
    protected open fun getSelectedParamName(): String = ""

    fun setRefreshing(isRefreshing: Boolean) {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout!!.isRefreshing = isRefreshing
        }
    }

    fun setRefreshingEnabled(isEnabled: Boolean) {
        swipeRefreshLayout?.isEnabled = isEnabled
    }

    // endregion

    override fun configureRecyclerView(
            rootView: View,
            context: Context,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int): PandaRecyclerView {
        return configureRecyclerView(rootView, context, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, resources.getString(R.string.noItemsToDisplayShort), false)
    }

    override fun configureRecyclerView(
            rootView: View,
            context: Context,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            emptyViewStringResId: Int): PandaRecyclerView {
        return configureRecyclerView(rootView, context, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, resources.getString(emptyViewStringResId), false)
    }

    override fun configureRecyclerView(
            rootView: View,
            context: Context,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            emptyViewString: String): PandaRecyclerView {
        return configureRecyclerView(rootView, context, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, emptyViewString, false)
    }

    override fun configureRecyclerView(
            rootView: View,
            context: Context,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            withDividers: Boolean): PandaRecyclerView {
        return configureRecyclerView(rootView, context, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, resources.getString(R.string.noItemsToDisplayShort), withDividers)
    }

    override fun configureRecyclerView(
            rootView: View,
            context: Context,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            emptyViewString: String,
            withDivider: Boolean): PandaRecyclerView {
        val emptyViewInterface = rootView.findViewById<View>(emptyViewResId) as EmptyViewInterface
        val recyclerView = rootView.findViewById<PandaRecyclerView>(recyclerViewResId)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.itemAnimator = DefaultItemAnimator()
        recyclerView.setEmptyView(emptyViewInterface)
        emptyViewInterface.emptyViewText(emptyViewString)
        emptyViewInterface.setNoConnectionText(getString(R.string.noConnection))
        recyclerView.isSelectionEnabled = true
        recyclerView.adapter = baseRecyclerAdapter
        if (withDivider) {
            recyclerView.addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL_LIST))
        }

        swipeRefreshLayout = rootView.findViewById(swipeRefreshLayoutResId)
        swipeRefreshLayout!!.setOnRefreshListener {
            if (!NetworkUtils.isNetworkAvailable) {
                swipeRefreshLayout!!.isRefreshing = false
            } else {
                baseRecyclerAdapter.refresh()
            }
        }

        return recyclerView
    }

    override fun configureRecyclerViewAsGrid(
            rootView: View,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int) {
        configureRecyclerViewAsGrid(rootView, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, R.string.noItemsToDisplayShort)
    }

    override fun configureRecyclerViewAsGrid(rootView: View, baseRecyclerAdapter: BaseRecyclerAdapter<*>, swipeRefreshLayoutResId: Int, emptyViewResId: Int, recyclerViewResId: Int, emptyViewStringResId: Int, span: Int) {
        configureRecyclerViewAsGrid(rootView, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, emptyViewStringResId, span, null)
    }

    override fun configureRecyclerViewAsGrid(
            rootView: View,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            emptyViewStringResId: Int,
            vararg emptyImage: Drawable) {
        configureRecyclerViewAsGrid(rootView, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, emptyViewStringResId, null, *emptyImage)
    }

    override fun configureRecyclerViewAsGrid(
            rootView: View,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            emptyViewStringResId: Int,
            emptyImageClickListener: View.OnClickListener?,
            vararg emptyImage: Drawable) {

        val minCardWidth = resources.getDimensionPixelOffset(R.dimen.course_card_min_width)
        val display = activity.windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val width = size.x
        val cardPadding = resources.getDimensionPixelOffset(R.dimen.card_outer_margin)

        //Sets a dynamic span size based on the min card width we need to display the color chooser.
        val span: Int
        if (width != 0) {
            span = width / (minCardWidth + cardPadding)
        } else {
            span = 1
        }

        configureRecyclerViewAsGrid(rootView, baseRecyclerAdapter, swipeRefreshLayoutResId, emptyViewResId, recyclerViewResId, emptyViewStringResId, if (span < 1) 1 else span, emptyImageClickListener, *emptyImage)
    }

    override fun configureRecyclerViewAsGrid(
            rootView: View,
            baseRecyclerAdapter: BaseRecyclerAdapter<*>,
            swipeRefreshLayoutResId: Int,
            emptyViewResId: Int,
            recyclerViewResId: Int,
            emptyViewStringResId: Int,
            span: Int,
            emptyImageClickListener: View.OnClickListener?,
            vararg emptyImage: Drawable) {

        val cardPadding = resources.getDimensionPixelOffset(R.dimen.card_outer_margin)
        val emptyViewInterface = rootView.findViewById<View>(emptyViewResId) as EmptyViewInterface
        val recyclerView = rootView.findViewById<PandaRecyclerView>(recyclerViewResId)
        emptyViewInterface.emptyViewText(emptyViewStringResId)
        emptyViewInterface.setNoConnectionText(getString(R.string.noConnection))

        if (emptyImage.isNotEmpty()) {
            emptyViewInterface.emptyViewImage(emptyImage[0])
            if (emptyImageClickListener != null && emptyViewInterface.emptyViewImage != null) {
                emptyViewInterface.emptyViewImage.setOnClickListener(emptyImageClickListener)
            }
        }

        val layoutManager = GridLayoutManager(context, span, GridLayoutManager.VERTICAL, false)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position < recyclerView.adapter.itemCount) {
                    val viewType = recyclerView.adapter.getItemViewType(position)
                    if (Types.TYPE_HEADER == viewType || PaginatedRecyclerAdapter.LOADING_FOOTER_TYPE == viewType) {
                        return span
                    }
                } else {
                    // If something goes wrong it will take up the entire space, but at least it won't crash
                    return span
                }
                return 1
            }
        }

        if (spacingDecoration != null) {
            recyclerView.removeItemDecoration(spacingDecoration)
        }

        spacingDecoration = if (baseRecyclerAdapter is ExpandableRecyclerAdapter<*, *, *>) {
            ExpandableGridSpacingDecorator(cardPadding)
        } else {
            GridSpacingDecorator(cardPadding)
        }
        recyclerView.addItemDecoration(spacingDecoration)


        recyclerView.layoutManager = layoutManager
        recyclerView.itemAnimator = DefaultItemAnimator()
        recyclerView.setEmptyView(emptyViewInterface)
        recyclerView.adapter = baseRecyclerAdapter
        swipeRefreshLayout = rootView.findViewById(swipeRefreshLayoutResId)
        swipeRefreshLayout!!.setOnRefreshListener {
            if (!com.instructure.pandautils.utils.Utils.isNetworkAvailable(context)) {
                swipeRefreshLayout!!.isRefreshing = false
            } else {
                baseRecyclerAdapter.refresh()
            }
        }
    }

    fun openMedia(mime: String?, url: String?, filename: String?, canvasContext: CanvasContext) {
        if (activity != null) {
            openMediaBundle = OpenMediaAsyncTaskLoader.createBundle(canvasContext, mime, url, filename)
            LoaderUtils.restartLoaderWithBundle<LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>>(activity.supportLoaderManager, openMediaBundle, loaderCallbacks, R.id.openMediaLoaderID)
        }
    }

    fun openMedia(isSubmission: Boolean, mime: String?, url: String?, filename: String?, canvasContext: CanvasContext) {
        if (activity != null) {
            openMediaBundle = OpenMediaAsyncTaskLoader.createBundle(canvasContext, isSubmission, mime, url, filename)
            LoaderUtils.restartLoaderWithBundle<LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>>(activity.supportLoaderManager, openMediaBundle, loaderCallbacks, R.id.openMediaLoaderID)
        }
    }

    fun openMedia(canvasContext: CanvasContext, url: String, filename: String?) {
        if (activity != null) {
            openMediaBundle = OpenMediaAsyncTaskLoader.createBundle(canvasContext, url, filename)
            LoaderUtils.restartLoaderWithBundle<LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>>(activity.supportLoaderManager, openMediaBundle, loaderCallbacks, R.id.openMediaLoaderID)
        }
    }

    fun openMedia(mime: String?, url: String?, filename: String?, useOutsideApps: Boolean, canvasContext: CanvasContext) {
        if (activity != null) {
            openMediaBundle = OpenMediaAsyncTaskLoader.createBundle(canvasContext, mime, url, filename, useOutsideApps)
            LoaderUtils.restartLoaderWithBundle<LoaderManager.LoaderCallbacks<OpenMediaAsyncTaskLoader.LoadedMedia>>(activity.supportLoaderManager, openMediaBundle, loaderCallbacks, R.id.openMediaLoaderID)
        }
    }

    private fun downloadFileToDownloadDir(context: Context, url: String): File? {
        // We should have the file cached locally at this point; We'll just move it to the user's Downloads folder

        if (!context.hasPermissions(PermissionUtils.WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(PermissionUtils.makeArray(PermissionUtils.WRITE_EXTERNAL_STORAGE), PermissionUtils.WRITE_FILE_PERMISSION_REQUEST_CODE)
            return null
        }

        Log.d(Const.OPEN_MEDIA_ASYNC_TASK_LOADER_LOG, "downloadFile URL: $url")
        val attachmentFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), loadedMedia!!.intent.data!!.lastPathSegment)

        // We've downloaded and cached this file already, so we'll just move it to the download directory
        val src = context.contentResolver.openInputStream(loadedMedia!!.intent.data!!)
        val dst = FileOutputStream(attachmentFile)

        val buffer = ByteArray(1024)
        var len: Int = src!!.read(buffer)
        while (len > 0) {
            dst.write(buffer, 0, len)
            len = src.read(buffer)
        }

        Toast.makeText(context, getString(R.string.downloadSuccessful), Toast.LENGTH_SHORT).show()

        return attachmentFile
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.WRITE_FILE_PERMISSION_REQUEST_CODE) {
            if (PermissionUtils.permissionGranted(permissions, grantResults, PermissionUtils.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(context, R.string.filePermissionGranted, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, R.string.filePermissionDenied, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showToast(stringResId: Int) {
        if (isAdded) {
            Toast.makeText(activity, stringResId, Toast.LENGTH_SHORT).show()
        }
    }

    fun showToast(message: String) {
        if (TextUtils.isEmpty(message)) {
            return
        }

        if (isAdded) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun showToast(stringResId: Int, length: Int) {
        if (isAdded) {
            Toast.makeText(activity, stringResId, length).show()
        }
    }

    fun showToast(message: String, length: Int) {
        if (TextUtils.isEmpty(message)) {
            return
        }

        if (isAdded) {
            Toast.makeText(activity, message, length).show()
        }
    }

    override fun getFragment(): Fragment? = this
}