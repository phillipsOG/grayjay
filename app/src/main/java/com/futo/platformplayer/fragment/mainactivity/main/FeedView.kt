package com.futo.platformplayer.fragment.mainactivity.main

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.LayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.futo.platformplayer.*
import com.futo.platformplayer.api.media.IPlatformClient
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.platforms.js.models.JSPager
import com.futo.platformplayer.api.media.structures.*
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.constructs.TaskHandler
import com.futo.platformplayer.engine.exceptions.PluginException
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.views.FeedStyle
import com.futo.platformplayer.views.adapters.ContentPreviewViewHolder
import com.futo.platformplayer.views.others.ProgressBar
import com.futo.platformplayer.views.others.TagsView
import com.futo.platformplayer.views.adapters.InsertedViewAdapterWithLoader
import com.futo.platformplayer.views.adapters.InsertedViewHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

abstract class FeedView<TFragment, TResult, TConverted, TPager, TViewHolder> : LinearLayout where TPager : IPager<TResult>, TViewHolder : RecyclerView.ViewHolder, TFragment : MainFragment {
    protected val  _recyclerResults: RecyclerView;
    protected val _overlayContainer: FrameLayout;
    protected val _swipeRefresh: SwipeRefreshLayout;
    private val _progress_bar: ProgressBar;
    private val _spinnerSortBy: Spinner;
    private val _containerSortBy: LinearLayout;
    private val _tagsView: TagsView;

    protected val _toolbarContentView: LinearLayout;

    private var _loading: Boolean = true;

    private val _pager_lock = Object();
    private var _cache: ItemCache<TResult>? = null;

    open val visibleThreshold = 15;

    protected abstract val feedStyle: FeedStyle;

    val onTagClick = Event1<String>();
    val onSortBySelect = Event1<String?>();

    private var _sortByOptions: List<String>? = null;
    private var _activeTags: List<String>? = null;

    private var _nextPageHandler: TaskHandler<TPager, List<TResult>>;
    val recyclerData: RecyclerData<InsertedViewAdapterWithLoader<TViewHolder>, LinearLayoutManager, TPager, TResult, TConverted, InsertedViewHolder<TViewHolder>>;

    val fragment: TFragment;

    private val _scrollListener: RecyclerView.OnScrollListener;

    constructor(fragment: TFragment, inflater: LayoutInflater, cachedRecyclerData: RecyclerData<InsertedViewAdapterWithLoader<TViewHolder>, LinearLayoutManager, TPager, TResult, TConverted, InsertedViewHolder<TViewHolder>>? = null) : super(inflater.context) {
        this.fragment = fragment;
        inflater.inflate(R.layout.fragment_feed, this);

        _progress_bar = findViewById(R.id.progress_bar);
        _progress_bar.inactiveColor = Color.TRANSPARENT;

        _swipeRefresh = findViewById(R.id.swipe_refresh);
        val recyclerResults: RecyclerView = findViewById(R.id.list_results);

        if (cachedRecyclerData != null) {
            recyclerData = cachedRecyclerData;
            onRestoreCachedData(cachedRecyclerData);
            attachParentPagerEvents();
            attachPagerEvents();
            setLoading(false);
        } else {
            val lmResults = createLayoutManager(recyclerResults, context);
            val dataset = arrayListOf<TConverted>();
            val adapterResults = createAdapter(recyclerResults, context, dataset);
            recyclerData = RecyclerData(adapterResults, lmResults, dataset);
        }

        _swipeRefresh.setOnRefreshListener {
            reload();
        };

        recyclerResults.layoutManager = recyclerData.layoutManager;
        recyclerResults.adapter = recyclerData.adapter;

        _overlayContainer = findViewById(R.id.overlay_container);
        _recyclerResults = recyclerResults;

        _containerSortBy = findViewById(R.id.container_sort_by);
        _spinnerSortBy = findViewById(R.id.spinner_sortby);
        _spinnerSortBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val sortByOptions = _sortByOptions ?: return;
                if (pos == 0) {
                    onSortBySelect.emit(null);
                    return;
                }

                onSortBySelect.emit(sortByOptions[pos - 1]);
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                onSortBySelect.emit(null);
            }
        };
        setSortByOptions(null);
        _tagsView = findViewById(R.id.tags_view);
        _tagsView.onClick.subscribe { onTagClick.emit(it.first) };
        setActiveTags(null);

        _toolbarContentView = findViewById(R.id.container_toolbar_content);

        _nextPageHandler = TaskHandler<TPager, List<TResult>>({fragment.lifecycleScope}, {
            if (it is IAsyncPager<*>)
                it.nextPageAsync();
            else
                it.nextPage();

            processPagerExceptions(it);
            return@TaskHandler it.getResults();
        }).success {
            setLoading(false);

            if (it.isEmpty()) {
                return@success;
            }

            val posBefore = recyclerData.results.size;
            val filteredResults = filterResults(it);
            recyclerData.results.addAll(filteredResults);
            recyclerData.resultsUnfiltered.addAll(it);
            recyclerData.adapter.notifyItemRangeInserted(recyclerData.adapter.childToParentPosition(posBefore), filteredResults.size);
        }.exception<Throwable> {
            Logger.w(TAG, "Failed to load next page.", it);
            UIDialogs.showGeneralRetryErrorDialog(context, "Failed to load next page", it, {
                loadNextPage();
            });
            //UIDialogs.showDataRetryDialog(layoutInflater, it.message, { loadNextPage() });
        };

        _scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState);
                onScrollStateChanged(newState);
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy);

                val visibleItemCount = _recyclerResults.childCount;
                val firstVisibleItem = recyclerData.layoutManager.findFirstVisibleItemPosition();
                if (!_loading && firstVisibleItem + visibleItemCount + visibleThreshold >= recyclerData.results.size && firstVisibleItem > 0) {
                    //Logger.i(TAG, "loadNextPage(): firstVisibleItem=$firstVisibleItem visibleItemCount=$visibleItemCount visibleThreshold=$visibleThreshold _results.size=${_results.size}")
                    loadNextPage();
                }
            }
        };

        _recyclerResults.addOnScrollListener(_scrollListener);
    }

    fun onResume() {
        //Reload the pager if the plugin was killed
        val pager = recyclerData.pager;
        if((pager is MultiPager<*> && pager.findPager { it is JSPager<*> && !it.isAvailable  } != null) ||
            (pager is JSPager<*> && !pager.isAvailable)) {
            Logger.w(TAG, "Detected pager of a dead plugin instance, reloading");
            reload();
        }
    }

    open fun cleanup() {
        detachParentPagerEvents();
        detachPagerEvents();

        _recyclerResults.removeOnScrollListener(_scrollListener);
        _nextPageHandler.cancel();

        _recyclerResults.adapter = null;
        _recyclerResults.layoutManager = null;
    }

    protected open fun onScrollStateChanged(newState: Int) {}

    protected open fun setActiveTags(activeTags: List<String>?) {
        _activeTags = activeTags;

        if (activeTags != null && activeTags.isNotEmpty()) {
            _tagsView.setTags(activeTags);
            _tagsView.visibility = View.VISIBLE;
        } else {
            _tagsView.visibility = View.GONE;
        }
    }
    protected open fun setSortByOptions(options: List<String>?) {
        _sortByOptions = options;

        if (options != null && options.isNotEmpty()) {
            val allOptions = arrayListOf<String>();
            allOptions.add("Default");
            allOptions.addAll(options);

            _spinnerSortBy.adapter = ArrayAdapter(context, R.layout.spinner_item_simple, allOptions).also {
                it.setDropDownViewResource(R.layout.spinner_dropdownitem_simple);
            };

            _containerSortBy.visibility = View.VISIBLE;
        } else {
            _containerSortBy.visibility = View.GONE;
        }
    }
    protected abstract fun createAdapter(recyclerResults: RecyclerView, context: Context, dataset: ArrayList<TConverted>): InsertedViewAdapterWithLoader<TViewHolder>;
    protected abstract fun createLayoutManager(recyclerResults: RecyclerView, context: Context): LinearLayoutManager;
    protected open fun onRestoreCachedData(cachedData: RecyclerData<InsertedViewAdapterWithLoader<TViewHolder>, LinearLayoutManager, TPager, TResult, TConverted, InsertedViewHolder<TViewHolder>>) {}

    protected fun setProgress(fin: Int, total: Int) {
        val progress = (fin.toFloat() / total);
        _progress_bar.progress = progress;
        if(progress > 0 && progress < 1)
        {
            if(_progress_bar.height == 0)
                _progress_bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 5);
        }
        else if(_progress_bar.height > 0) {
            _progress_bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        }
    }

    private fun processPagerExceptions(pager: IPager<*>) {
        if(pager is MultiPager<*> && pager.allowFailure) {
            val ex = pager.getResultExceptions();
            for(kv in ex) {
                val jsVideoPager: JSPager<*>? = if(kv.key is MultiPager<*>)
                    (kv.key as MultiPager<*>).findPager { it is JSPager<*> } as JSPager<*>?;
                else if(kv.key is JSPager<*>)
                    kv.key as JSPager<*>;
                else null;

                context?.let {
                    fragment.lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            if(jsVideoPager != null)
                                UIDialogs.toast(it, "Plugin ${jsVideoPager.getPluginConfig().name} failed:\n${kv.value.message}", false);
                            else
                                UIDialogs.toast(it, kv.value.message ?: "", false);
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Failed to show toast.", e)
                        }
                    }
                }
            }
        }
    }

    open fun filterResults(results: List<TResult>): List<TConverted> {
        return results as List<TConverted>;
    }

    open fun reload() {

    }

    protected fun finishRefreshLayoutLoader() {
        _swipeRefresh.isRefreshing = false;
    }

    fun clearResults(){
        setPager(EmptyPager<TResult>() as TPager);
    }

    fun preloadCache(cache: ItemCache<TResult>) {
        _cache = cache
        recyclerData.results.clear();
        val results = cache.cachePager.getResults();
        val resultsFiltered = filterResults(results);
        recyclerData.results.addAll(resultsFiltered);
        recyclerData.adapter.notifyDataSetChanged();
        //insertPagerResults(_cache!!.cachePager.getResults(), false);
    }
    fun setPager(pager: TPager, cache: ItemCache<TResult>? = null) {
        synchronized(_pager_lock) {
            detachParentPagerEvents();
            detachPagerEvents();

            val pagerToSet: TPager?;
            if(pager is IRefreshPager<*>) {
                recyclerData.parentPager = pager;
                attachParentPagerEvents();
                pagerToSet = pager.getCurrentPager() as TPager;
            }
            else pagerToSet = pager;

            loadPagerInternal(pagerToSet, cache);
        }
    }

    private fun detachParentPagerEvents() {
        val parentPager = recyclerData.parentPager;
        if (parentPager != null && parentPager is IRefreshPager<*>) {
            parentPager.onPagerError.remove(this);
            parentPager.onPagerChanged.remove(this);
            recyclerData.parentPager = null;
        }
    }

    private fun attachParentPagerEvents() {
        val parentPager = recyclerData.parentPager as IRefreshPager<*>? ?: return;
        parentPager.onPagerChanged.subscribe(this) {
            fragment.lifecycleScope.launch(Dispatchers.Main) {
                try {
                    loadPagerInternal(it as TPager);
                } catch (e: Throwable) {
                    Logger.e(TAG, "Failed loadPagerInternal", e)
                }
            }
        };
        parentPager.onPagerError.subscribe(this) {
            Logger.e(TAG, "Search pager failed: ${it.message}", it);
            when (it) {
                is PluginException -> UIDialogs.toast("Plugin [${it.config.name}] failed due to:\n${it.message}")
                is CancellationException -> {
                    //Hide cancelled toast
                }
                else -> UIDialogs.toast("Plugin failed due to:\n${it.message}")
            };
        };
    }

    private fun loadPagerInternal(pager: TPager, cache: ItemCache<TResult>? = null) {
        _cache = cache;

        detachPagerEvents();
        recyclerData.pager = pager;
        attachPagerEvents();

        processPagerExceptions(pager);

        recyclerData.results.clear();
        recyclerData.resultsUnfiltered.clear();
        val toAdd = pager.getResults();
        val filteredResults = filterResults(toAdd);
        recyclerData.results.addAll(filteredResults);
        //insertPagerResults(toAdd, true);
        recyclerData.resultsUnfiltered.addAll(toAdd);
        recyclerData.adapter.notifyDataSetChanged();
        recyclerData.loadedFeedStyle = feedStyle;
    }

    private fun detachPagerEvents() {
        val p = recyclerData.pager;
        if(p is IReplacerPager<*>)
            p.onReplaced.remove(this);
    }

    private fun attachPagerEvents() {
        val p = recyclerData.pager;
        if(p is IReplacerPager<*>) {
            p.onReplaced.subscribe(this) { _, newItem ->
                synchronized(_pager_lock) {
                    val filtered = filterResults(listOf(newItem as TResult));
                    if(filtered.isEmpty())
                        return@subscribe;
                    val newItemConverted = filtered[0];

                    val toReplaceIndex = recyclerData.results.indexOfFirst { it == newItemConverted };
                    if(toReplaceIndex >= 0) {
                        recyclerData.results[toReplaceIndex] = newItemConverted;
                        recyclerData.adapter.notifyItemChanged(recyclerData.adapter.childToParentPosition(toReplaceIndex));
                    }
                }
            }
        }
    }

    private fun loadNextPage() {
        synchronized(_pager_lock) {
            val pager: TPager = recyclerData.pager ?: return;
            val hasMorePages = pager.hasMorePages();
            Logger.i(TAG, "loadNextPage() hasMorePages=$hasMorePages");

            //loadCachedPage();
            if (pager.hasMorePages()) {
                setLoading(true);
                _nextPageHandler.run(pager);
            }
        }
    }

    protected fun setLoading(loading: Boolean) {
        Logger.v(TAG, "setLoading loading=${loading}");
        _loading = loading;
        recyclerData.adapter.setLoading(loading);
    }

    companion object {
        private val TAG = "FeedView";
    }

    abstract class ItemCache<TResult>(val cachePager: IPager<TResult>) {
        abstract fun isSame(item: TResult, toCompare: TResult): Boolean;
        abstract fun compareOrder(item: TResult, toCompare: TResult): Int;
    }

    data class RecyclerData<TAdapter, TLayoutManager, TPager, TResult, TConverted, TViewHolder> (
        val adapter: TAdapter,
        val layoutManager: TLayoutManager,
        val results: ArrayList<TConverted>,
        val resultsUnfiltered: ArrayList<TResult> = ArrayList(),
        var pager: TPager? = null,
        var parentPager: TPager? = null,
        var loadedFeedStyle: FeedStyle = FeedStyle.UNKNOWN,
        var lastLoad: OffsetDateTime = OffsetDateTime.MIN,
        var lastClients: List<IPlatformClient>? = null
    ) where TViewHolder : RecyclerView.ViewHolder, TPager : IPager<TResult>, TAdapter : RecyclerView.Adapter<TViewHolder>, TLayoutManager : LayoutManager
}