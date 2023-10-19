package com.futo.platformplayer.fragment.mainactivity.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.futo.platformplayer.R
import com.futo.platformplayer.views.adapters.SubscriptionAdapter

class CreatorsFragment : MainFragment() {
    override val isMainView : Boolean = true;
    override val isTab: Boolean = true;
    override val hasBottomBar: Boolean get() = true;

    private var _spinnerSortBy: Spinner? = null;

    override fun onCreateMainView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_creators, container, false);

        val adapter = SubscriptionAdapter(inflater, getString(R.string.confirm_delete_subscription));
        adapter.onClick.subscribe { platformUser -> navigate<ChannelFragment>(platformUser) };

        val spinnerSortBy: Spinner = view.findViewById(R.id.spinner_sortby);
        spinnerSortBy.adapter = ArrayAdapter(view.context, R.layout.spinner_item_simple, resources.getStringArray(R.array.subscriptions_sortby_array)).also {
            it.setDropDownViewResource(R.layout.spinner_dropdownitem_simple);
        };
        spinnerSortBy.setSelection(adapter.sortBy);
        spinnerSortBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                adapter.sortBy = pos;
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        };

        _spinnerSortBy = spinnerSortBy;

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_subscriptions);
        recyclerView.adapter = adapter;
        recyclerView.layoutManager = LinearLayoutManager(view.context);
        return view;
    }

    override fun onDestroyMainView() {
        super.onDestroyMainView();
        _spinnerSortBy = null;
    }

    companion object {
        fun newInstance() = CreatorsFragment().apply {}
    }
}