package sk.fei.videoeditor.activities;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ads.AdsMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;

import com.google.android.exoplayer2.ui.DefaultTimeBar;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.otaliastudios.cameraview.VideoResult;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

import sk.fei.videoeditor.R;
import sk.fei.videoeditor.workers.FFmpegWorker;

public class VideoPreview extends AppCompatActivity implements AdsMediaSource.MediaSourceFactory {

    Uri videoUri;
    String TAG = "Video Preview";
    private String mode;
    SimpleExoPlayer player;
    SimpleExoPlayerView playerView;
    MediaPlayer audioPlayer;
    int duration;
    File dest;
    String[] command;
    String path;
    private Uri audioUri;
    private static VideoResult videoResult;
    private boolean isSaved = false;
    private int startMs;
    private int endMs;
    Constraints mConstraints;
    MediaSource video,audio;
    private  DataSource.Factory dataSourceFactory;
    private Dialog mFullScreenDialog;
    float pts;
    Handler handler;

    ImageView close,save;
    DefaultTimeBar timeBar;
    TextView exoCurrentPosition, exoDuration;

    public static void setVideoResult(@Nullable VideoResult result) {
        videoResult = result;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getSupportActionBar().hide();
        setContentView(R.layout.activity_video_view);
        setDataFromIntent();
        setViewsLayout();
    }


    private String createOutputFilePath(){
        File folder = new File(Environment.getExternalStorageDirectory() + "/My Video Editor");
        if (!folder.exists()) {
            folder.mkdir();
        }

        @SuppressLint("SimpleDateFormat") String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "CAMERA_" + timestamp + "_";

        String fileExt = ".mp4";
        dest = new File(folder, prepend + fileExt);

        return dest.getAbsolutePath();
    }

    private void setViewsLayout(){
        timeBar = findViewById(R.id.exo_progress);
        exoCurrentPosition = findViewById(R.id.exo_position);
        exoDuration = findViewById(R.id.exo_duration);

        dataSourceFactory =
                new DefaultDataSourceFactory(
                        this, Util.getUserAgent(this, getString(R.string.video_preview)));
    }

    private boolean setRetake(){

        if(isSaved){
            clearAllActivities();
           return true;
        }else{
            if(videoUri != null && !mode.equals("trimmedVideo")){
                File file = new File(Objects.requireNonNull(videoUri.getPath()));
                if(file.exists()){
                    finish();
                    return file.delete();
                }
            }else if(mode.equals("trimmedVideo")){
                finish();
            }
        }
        return false;
    }

    private void clearAllActivities(){
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private boolean createWorker(){
        mConstraints = new Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest mRequest = new OneTimeWorkRequest.Builder(FFmpegWorker.class)
                .setInputData(createInputData(command,duration,dest.getPath()))
                .build();

        WorkManager mWorkManager = WorkManager.getInstance();
        mWorkManager.beginWith(mRequest).enqueue();

        mWorkManager.getWorkInfoByIdLiveData(mRequest.getId()).observe(this, workInfo -> {
            if (workInfo != null) {
                if(workInfo.getState() == WorkInfo.State.SUCCEEDED){
                    isSaved = true;
                }
                Log.d("videoPreview", "work state = " + workInfo.getState());
            }
        });
        return isSaved;
    }

    private void setDataFromIntent(){

        Intent i = getIntent();

        if(i!=null) {
            mode = i.getStringExtra("mode");
            if(mode.equals("trimmedVideo")){
                duration = i.getIntExtra("duration", 0);
                command = i.getStringArrayExtra("command");
                path = i.getStringExtra("destination");
                dest = new File(path);
                startMs = i.getIntExtra("startMs", 0);
                endMs = i.getIntExtra("endMs", 0);
                audioUri = Uri.parse(i.getStringExtra("audioUri"));
                videoUri = Uri.fromFile(new File(Objects.requireNonNull(i.getStringExtra("videoUri"))));

            }
            else{
                createOutputFilePath();
                duration = videoResult.getMaxDuration();
                audioUri = Uri.parse(i.getStringExtra("audioUri"));
                videoUri = Uri.fromFile(videoResult.getFile());
                if(mode.equals("maxDurationReached")){
                    command = new String[]{"-i" ,videoResult.getFile().getPath(), "-i", audioUri.getPath(), "-map", "0:v", "-map", "1:a", "-c", "copy", "-shortest",dest.getAbsolutePath()};
                }
                else if(mode.equals("earlyStop")){
                    pts = getVideoDuration()/(float)getAudioDuration();
                    command = new String[]{"-i", videoResult.getFile().getPath(),"-i", audioUri.getPath(), "-filter:v", "setpts=PTS/"+pts+"[v]","-acodec", "copy", dest.getAbsolutePath()};
                }
            }
        }
    }

    public int getAudioDuration(){
        MediaPlayer mp  = new MediaPlayer();
        try {
            mp.setDataSource(this, audioUri);
            mp.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return mp.getDuration();
    }

    public int getVideoDuration(){
        MediaPlayer mp  = new MediaPlayer();
        try {
            mp.setDataSource(videoResult.getFile().getPath());
            mp.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return mp.getDuration();
    }


    @Override
    public boolean onPrepareOptionsMenu (Menu menu) {
        MenuItem item = menu.findItem(R.id.save);

        if (isSaved) {
            item.setEnabled(false);
        }else{
            item.setEnabled(true);
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.video_preview_save,menu);
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {


        switch (item.getItemId()){
            case android.R.id.home:
                finish();
                break;

            case R.id.save :

        }

        return super.onOptionsItemSelected(item);
    }

    public Data createInputData(String[] command, int duration, String path){
        return new Data.Builder()
                .putStringArray("command", command)
                .putInt("duration", duration)
                .putString("path",path)
                .build();
    }



    private ExoPlayer.EventListener eventListener = new ExoPlayer.EventListener() {

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
            Log.i(TAG,"onTracksChanged");
        }

        @Override
        public void onLoadingChanged(boolean isLoading) {
            Log.i(TAG,"onLoadingChanged");
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            Log.i(TAG,"onPlayerStateChanged: playWhenReady = "+(playWhenReady)
                    +" playbackState = "+playbackState);


            if(audioPlayer != null && playbackState != ExoPlayer.STATE_READY){
                if(mode.equals("earlyStop")){
                    audioPlayer.seekTo((int)(player.getCurrentPosition()/pts));

                }else{
                    audioPlayer.seekTo((int)player.getCurrentPosition());

                }
            }

            switch (playbackState){
                case ExoPlayer.STATE_ENDED:
                    Log.i(TAG,"Playback ended!");
                    //Stop playback and return to start position
                    if(audioPlayer != null){
                        audioPlayer.pause();
                    }
                    break;
                case ExoPlayer.STATE_READY:
                    Log.i(TAG,"State ready!");
                    if(audioPlayer != null && playWhenReady){
                        audioPlayer.start();
                    }else{
                        if(audioPlayer != null){
                            audioPlayer.pause();
                        }

                    }
                    break;
                case ExoPlayer.STATE_BUFFERING:
                    Log.i(TAG,"Playback buffering!");
                    break;
                case ExoPlayer.STATE_IDLE:
                    Log.i(TAG,"ExoPlayer idle!");
                    break;
            }
        }

    };


    @Override
    protected void onStart() {
        super.onStart();

    }

    @Override
    public void onPause() {
        super.onPause();
        if (player!=null) {
            player.release();
            player = null;
        }
        if (audioPlayer!=null) {
            audioPlayer.release();
            audioPlayer = null;
        }

    }

    private void initExoControlers(){
        playerView =  findViewById(R.id.exoplayer);
        playerView.findViewById(R.id.exo_share).setVisibility(View.GONE);
        playerView.findViewById(R.id.exo_delete).setVisibility(View.GONE);

        close = playerView.findViewById(R.id.exo_close);
        save = playerView.findViewById(R.id.exo_save);
        save.setVisibility(View.VISIBLE);

        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mFullScreenDialog.dismiss();
                setRetake();
            }
        });


        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Drawable resIcon = getResources().getDrawable(R.drawable.ic_file_download_white_24dp);
                createWorker();
                save.setEnabled(false);
                isSaved = true;
                resIcon.mutate().setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN);
                save.setImageDrawable(resIcon);
            }
        });


    }

    @Override
    protected void onResume() {

        super.onResume();

        if (playerView == null) {
            initExoControlers();
            initFullscreenDialog();
        }
        initializePlayer();
        openFullscreenDialog();

        ((ViewGroup) playerView.getParent()).removeView(playerView);
        mFullScreenDialog.addContentView(playerView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // mFullScreenIcon.setImageDrawable(ContextCompat.getDrawable(VideoViewer.this, R.mipmap.ic_fullscreen_skrink_foreground));
        mFullScreenDialog.show();

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initializePlayer() {
        // Create a default TrackSelector
        BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        TrackSelection.Factory videoTrackSelectionFactory =
                new AdaptiveTrackSelection.Factory(bandwidthMeter);
        TrackSelector trackSelector =
                new DefaultTrackSelector(videoTrackSelectionFactory);

        //Initialize the player
        player = ExoPlayerFactory.newSimpleInstance(this, trackSelector);
        //Initialize simpleExoPlayerView
        playerView = findViewById(R.id.exoplayer);
        playerView.setPlayer(player);


        audio = buildMediaSource(audioUri);
        video = buildMediaSource(videoUri);

        ConcatenatingMediaSource mergedSource = new ConcatenatingMediaSource();
        mergedSource.addMediaSource(setMediaSource(audio,video));

        player.addListener(eventListener);

        if(mode.equals("trimmedVideo")){
            player.setVolume(0f);
        }
        else if(mode.equals("earlyStop")){
            handler = new Handler();
            timeBar.setPosition(getAudioDuration());
            exoDuration.setText(getTime(getAudioDuration()));
            player.setPlaybackParameters(new PlaybackParameters(pts));
        }
        player.prepare(mergedSource);
        player.setPlayWhenReady(true);
        //audioPlayer.prepare(audio);

    }

    private MediaSource buildMediaSource(Uri uri) {
        @C.ContentType int type = Util.inferContentType(uri);
        switch (type) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            case C.TYPE_SS:
                return new SsMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            default:
                throw new IllegalStateException("Unsupported type: " + type);
        }
    }

    private MergingMediaSource setMediaSource(MediaSource audio, MediaSource video) {
        MergingMediaSource mergedMediaSource = new MergingMediaSource();
        if(mode.equals("trimmedVideo")){
            initMediaPlayer();

            ClippingMediaSource clippingMediaSource = new ClippingMediaSource(video,startMs*1000,endMs*1000);
            mergedMediaSource = new MergingMediaSource(clippingMediaSource);
            Log.d(TAG,"startMs: "+ startMs +" endMs: "+endMs +" ...set Media Source");
        }else if(mode.equals("maxDurationReached")){
            mergedMediaSource = new MergingMediaSource(video,audio);
        }
        else if(mode.equals("earlyStop")){
            initMediaPlayer();
            mergedMediaSource = new MergingMediaSource(video);
        }
        return mergedMediaSource;
    }

    private void initMediaPlayer(){
       audioPlayer = new MediaPlayer();
        try {
            audioPlayer.setDataSource(audioUri.getPath());
            audioPlayer.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
        audioPlayer.start();
    }

    @Override
    public MediaSource createMediaSource(Uri uri) {
        return buildMediaSource(uri);
    }

    @Override
    public int[] getSupportedTypes() {
        return new int[] {C.TYPE_DASH, C.TYPE_HLS, C.TYPE_OTHER};
    }
    @SuppressLint("DefaultLocale")
    public String getTime(int miliseconds) {

        int ms = miliseconds % 1000;
        int rem = miliseconds / 1000;
        int hr = rem / 3600;
        int remHr = rem % 3600;
        int mn = remHr / 60;
        int sec = remHr % 60;

        if(hr==0 && mode.equals("earlyStop")){
            return  String.format("%02d", mn) + ':' + String.format("%02d", sec);
        }
        return String.format("%02d", hr) + ':' + String.format("%02d", mn) + ':' + String.format("%02d", sec)+ '.' + String.format("%03d", ms);

    }


    private void initFullscreenDialog() {

        mFullScreenDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            public void onBackPressed() {
                mFullScreenDialog.dismiss();
                if(isSaved){
                    clearAllActivities();
                }else{
                    supportFinishAfterTransition();
                }
            }
        };

    }

    private void openFullscreenDialog() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        ((ViewGroup) playerView.getParent()).removeView(playerView);
        mFullScreenDialog.addContentView(playerView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        //mFullScreenIcon.setImageDrawable(ContextCompat.getDrawable(VideoViewer.this, R.mipmap.ic_fullscreen_skrink_foreground));
        mFullScreenDialog.show();

    }

}