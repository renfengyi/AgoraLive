package io.agora.vlive.ui.main.fragments;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

import io.agora.vlive.R;
import io.agora.vlive.proxy.ClientProxy;
import io.agora.vlive.proxy.struts.model.RoomInfo;
import io.agora.vlive.proxy.struts.request.Request;
import io.agora.vlive.proxy.struts.request.RoomListRequest;
import io.agora.vlive.proxy.struts.response.RoomListResponse;
import io.agora.vlive.utils.Global;

public abstract class AbsPageFragment extends AbstractFragment implements SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = AbsPageFragment.class.getSimpleName();

    private static final int SPAN_COUNT = 2;
    private static final int REFRESH_DELAY = 1000 * 60;

    // By default, the client asks for 10 more rooms to show in the list
    private static final int REQ_ROOM_COUNT = 10;

    private Handler mHandler;
    private PageRefreshRunnable mPageRefreshRunnable;

    private SwipeRefreshLayout mSwipeRefreshLayout;
    private RecyclerView mRecyclerView;
    private RoomListAdapter mAdapter;
    private View mNoDataBg;

    private int mItemSpacing;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        mPageRefreshRunnable = new PageRefreshRunnable();
        mItemSpacing = getContainer().getResources()
                .getDimensionPixelSize(R.dimen.activity_horizontal_margin);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View layout = inflater.inflate(R.layout.fragment_room_list, container, false);
        mSwipeRefreshLayout = layout.findViewById(R.id.host_in_swipe);
        mSwipeRefreshLayout.setNestedScrollingEnabled(true);
        mSwipeRefreshLayout.setOnRefreshListener(this);

        mRecyclerView = layout.findViewById(R.id.host_in_room_list_recycler);
        mRecyclerView.setVisibility(View.VISIBLE);
        mRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), SPAN_COUNT));
        mAdapter = new RoomListAdapter();
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.addItemDecoration(new RoomListItemDecoration());
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    Log.i(TAG, "page scroll state: drag");
                    stopRefreshTimer();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    startRefreshTimer();

                    Log.i(TAG, "page scroll state: idle");
                    int lastItemPosition = recyclerView.getChildAdapterPosition(
                            recyclerView.getChildAt(recyclerView.getChildCount() - 1));
                    if (lastItemPosition == recyclerView.getAdapter().getItemCount() - 1) {
                        Log.i(TAG, "last item is reached");
                        refreshPage(mAdapter.getLast() == null ? null : mAdapter.getLast().roomId);
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
            }
        });

        mNoDataBg = layout.findViewById(R.id.no_data_bg);
        checkRoomListEmpty();

        return layout;
    }

    private void checkRoomListEmpty() {
        mRecyclerView.setVisibility(mAdapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        mNoDataBg.setVisibility(mAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void startRefreshTimer() {
        mHandler.postDelayed(mPageRefreshRunnable, REFRESH_DELAY);
    }

    private void stopRefreshTimer() {
        mHandler.removeCallbacks(mPageRefreshRunnable);
    }

    private class PageRefreshRunnable implements Runnable {
        @Override
        public void run() {
            onPeriodicRefreshTimerTicked();
            mHandler.postDelayed(mPageRefreshRunnable, REFRESH_DELAY);
        }
    }

    private void onPeriodicRefreshTimerTicked() {
        refreshPage(null);
    }

    @Override
    public void onRefresh() {
        Log.i(TAG, "onPageRefresh");
        refreshPage(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        startRefreshTimer();
        getContainer().proxy().registerProxyListener(this);
        refreshPage(null);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
        stopRefreshTimer();
        getContainer().proxy().removeProxyListener(this);
    }

    /**
     * Refresh the page from after a specific room.
     * @param nextId null if refresh from the beginning of list
     */
    private void refreshPage(String nextId) {
        refreshPage(nextId, REQ_ROOM_COUNT, onGetRoomListType(), ClientProxy.PK_UNAWARE);
    }

    private void refreshPage(String nextId, int count, int type, int pkState) {
        RoomListRequest request = new RoomListRequest();
        request.nextId = nextId;
        request.count = count;
        request.type = type;
        request.pkState = pkState;
        getContainer().proxy().sendRequest(Request.ROOM_LIST, request);
    }

    @Override
    public void onRoomListResponse(RoomListResponse response) {
        final List<RoomInfo> list = response.data.list;
        getContainer().runOnUiThread(() -> {
            if (response.data.next == null) {
                // this page refreshes from the start
                mAdapter.reset(list);
            } else {
                mAdapter.append(list);
            }

            checkRoomListEmpty();
            if (mSwipeRefreshLayout.isRefreshing()) {
                mSwipeRefreshLayout.setRefreshing(false);
            }
        });
    }

    private class RoomListAdapter extends RecyclerView.Adapter {
        private List<RoomInfo> mRoomList = new ArrayList<>();

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RoomListItemViewHolder(LayoutInflater.from(getContext()).
                    inflate(R.layout.live_room_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, final int position) {
            RoomInfo info = mRoomList.get(position);
            RoomListItemViewHolder itemHolder = (RoomListItemViewHolder) holder;
            itemHolder.name.setText(info.roomName);
            itemHolder.count.setText(String.valueOf(info.currentUsers));
            itemHolder.itemView.setOnClickListener((view) -> {
                goLiveRoom(mRoomList.get(position), onGetRoomListType());
            });
        }

        @Override
        public int getItemCount() {
            return mRoomList.size();
        }

        void append(List<RoomInfo> infoList) {
            mRoomList.addAll(infoList);
            notifyDataSetChanged();
        }

        void reset(List<RoomInfo> infoList) {
            mRoomList.clear();
            append(infoList);
            notifyDataSetChanged();
        }

        RoomInfo getLast() {
            return mRoomList.isEmpty() ? null : mRoomList.get(mRoomList.size() - 1);
        }
    }

    private void goLiveRoom(RoomInfo info, int roomType) {
        Intent intent = new Intent(getActivity(), getLiveActivityClass());
        intent.putExtra(Global.Constants.TAB_KEY, roomType);
        intent.putExtra(Global.Constants.KEY_IS_ROOM_OWNER, false);
        intent.putExtra(Global.Constants.KEY_ROOM_NAME, info.roomName);
        intent.putExtra(Global.Constants.KEY_ROOM_OWNER_ID, info.ownerUid);
        startActivity(intent);
    }

    private class RoomListItemViewHolder extends RecyclerView.ViewHolder {
        AppCompatTextView count;
        AppCompatTextView name;
        RoomListItemViewHolder(@NonNull View itemView) {
            super(itemView);
            count = itemView.findViewById(R.id.live_room_list_item_count);
            name = itemView.findViewById(R.id.live_room_list_item_room_name);
        }
    }

    private class RoomListItemDecoration extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            super.getItemOffsets(outRect, view, parent, state);

            int position = parent.getChildAdapterPosition(view);
            int total = parent.getAdapter() == null ? 0 : parent.getAdapter().getItemCount();
            int half = mItemSpacing / 2;

            outRect.top = half;
            outRect.bottom = half;

            if (position < SPAN_COUNT) {
                outRect.top = mItemSpacing;
            } else {
                int remain = total % SPAN_COUNT;
                if (remain == 0) remain = SPAN_COUNT;
                if (position + remain >= total) {
                    outRect.bottom = mItemSpacing;
                }
            }

            if (position % SPAN_COUNT == 0) {
                outRect.left = mItemSpacing;
                outRect.right = mItemSpacing / 2;
            } else {
                outRect.left = mItemSpacing / 2;
                outRect.right = mItemSpacing;
            }
        }
    }

    protected abstract int onGetRoomListType();

    protected abstract Class<?> getLiveActivityClass();
}