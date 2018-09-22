/*
 * This is the source code of DMAudioStreaming for Android v. 1.0.0.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Copyright @Dibakar_Mistry(dibakar.ece@gmail.com), 2017.
 */
package dm.audiostreamer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import com.nostra13.universalimageloader.core.ImageLoader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AudioStreamingService extends Service
	implements NotificationManager.NotificationCenterDelegate {
	private static final String TAG = Logger.makeLogTag(AudioStreamingService.class);

	public static final String EXTRA_CONNECTED_CAST = "dm.audiostreaming.CAST_NAME";
	public static final String ACTION_CMD = "dm.audiostreaming.ACTION_CMD";
	public static final String CMD_NAME = "CMD_NAME";
	public static final String CMD_PAUSE = "CMD_PAUSE";
	public static final String CMD_STOP_CASTING = "CMD_STOP_CASTING";
	private static final int STOP_DELAY = 30000;

	public static final String NOTIFY_PREVIOUS = "dm.audiostreamer.previous";
	public static final String NOTIFY_CLOSE = "dm.audiostreamer.close";
	public static final String NOTIFY_PAUSE = "dm.audiostreamer.pause";
	public static final String NOTIFY_PLAY = "dm.audiostreamer.play";
	public static final String NOTIFY_NEXT = "dm.audiostreamer.next";

	private static boolean supportBigNotifications =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
	private static boolean supportLockScreenControls =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
	private RemoteControlClient remoteControlClient;
	private AudioManager audioManager;
	private AudioStreamingManager audioStreamingManager;
	private PhoneStateListener phoneStateListener;
	public PendingIntent pendingIntent;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				audioStreamingManager = AudioStreamingManager.getInstance(AudioStreamingService.this);
				audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
				NotificationManager.getInstance()
					.addObserver(this, NotificationManager.audioProgressDidChanged);
				NotificationManager.getInstance()
					.addObserver(this, NotificationManager.setAnyPendingIntent);
				NotificationManager.getInstance()
					.addObserver(this, NotificationManager.audioPlayStateChanged);
				try {
					phoneStateListener = new PhoneStateListener() {
						@Override
						public void onCallStateChanged(int state, String incomingNumber) {
							if (state == TelephonyManager.CALL_STATE_RINGING) {
								if (audioStreamingManager.isPlaying()) {
									audioStreamingManager.handlePauseRequest();
								}
							} else if (state == TelephonyManager.CALL_STATE_IDLE) {

							} else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {

							}
							super.onCallStateChanged(state, incomingNumber);
						}
					};
					TelephonyManager mgr = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
					if (mgr != null) {
						mgr.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
					}
				} catch (Exception e) {
					Log.e("tmessages", e.toString());
				}
			}
		});
		super.onCreate();
	}

	@Override
	public int onStartCommand(Intent startIntent, int flags, int startId) {
		try {
			MediaMetaData messageObject =
				AudioStreamingManager.getInstance(AudioStreamingService.this).getCurrentAudio();
			if (messageObject == null) {
				Handler handler = new Handler(AudioStreamingService.this.getMainLooper());
				handler.post(new Runnable() {
					@Override
					public void run() {
						stopSelf();
					}
				});
				return START_STICKY;
			}

			if (supportLockScreenControls) {
				ComponentName remoteComponentName =
					new ComponentName(getApplicationContext(), AudioStreamingReceiver.class.getName());
				try {
					if (remoteControlClient == null) {
						audioManager.registerMediaButtonEventReceiver(remoteComponentName);
						Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
						mediaButtonIntent.setComponent(remoteComponentName);
						PendingIntent mediaPendingIntent =
							PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
						remoteControlClient = new RemoteControlClient(mediaPendingIntent);
						audioManager.registerRemoteControlClient(remoteControlClient);
					}
					remoteControlClient.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
						| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
						| RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
						| RemoteControlClient.FLAG_KEY_MEDIA_STOP
						| RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS
						| RemoteControlClient.FLAG_KEY_MEDIA_NEXT);
				} catch (Exception e) {
					Log.e("tmessages", e.toString());
				}
			}
			createNotification(messageObject);
		} catch (Exception e) {

		}
		if (startIntent != null) {
			String action = startIntent.getAction();
			String command = startIntent.getStringExtra(CMD_NAME);
			if (ACTION_CMD.equals(action)) {
				if (CMD_PAUSE.equals(command)) {
					audioStreamingManager.handlePauseRequest();
				} else if (CMD_STOP_CASTING.equals(command)) {
					//TODO FOR EXTERNAL DEVICE
				}
			}
		}
		return START_NOT_STICKY;
	}

	private void createNotification(MediaMetaData mSongDetail) {
		try {
			String songName = mSongDetail.getMediaTitle();
			String authorName = mSongDetail.getMediaArtist();
			String albumName = mSongDetail.getMediaAlbum();
			MediaMetaData audioInfo =
				AudioStreamingManager.getInstance(AudioStreamingService.this).getCurrentAudio();

			RemoteViews simpleContentView = new RemoteViews(getApplicationContext().getPackageName(),
				R.layout.player_small_notification);
			RemoteViews expandedView = null;
			if (supportBigNotifications) {
				expandedView = new RemoteViews(getApplicationContext().getPackageName(),
					R.layout.player_big_notification);
			}

			Notification notification = null;
			if (pendingIntent != null) {
				notification =
					new NotificationCompat.Builder(getApplicationContext()).setSmallIcon(R.drawable.player)
						.setContentIntent(pendingIntent)
						.setPriority(2)
						.setContentTitle(songName)
						.build();
			} else {
				notification =
					new NotificationCompat.Builder(getApplicationContext()).setSmallIcon(R.drawable.player)
						.setContentTitle(songName)
						.setPriority(2)
						.build();
			}

			notification.contentView = simpleContentView;
			if (supportBigNotifications) {
				notification.bigContentView = expandedView;
			}

			setListeners(simpleContentView);
			if (supportBigNotifications) {
				setListeners(expandedView);
			}

			Bitmap albumArt = null;
			try {
				ImageLoader imageLoader = ImageLoader.getInstance();
				albumArt = imageLoader.loadImageSync(audioInfo.getMediaArt());
			} catch (Exception e) {
				try {
					albumArt = new AsyncTaskRunner().execute(audioInfo.getMediaArt()).get();
				} catch (Exception ee) {
					System.out.println(ee);
				}
				e.printStackTrace();
			}

			if (albumArt != null) {
				notification.contentView.setImageViewBitmap(R.id.player_album_art, albumArt);
				if (supportBigNotifications) {
					notification.bigContentView.setImageViewBitmap(R.id.player_album_art, albumArt);
				}
			} else {
				notification.contentView.setImageViewResource(R.id.player_album_art,
					R.drawable.bg_default_album_art);
				if (supportBigNotifications) {
					notification.bigContentView.setImageViewResource(R.id.player_album_art,
						R.drawable.bg_default_album_art);
				}
			}
			notification.contentView.setViewVisibility(R.id.player_progress_bar, View.GONE);
			notification.contentView.setViewVisibility(R.id.player_next, View.VISIBLE);
			notification.contentView.setViewVisibility(R.id.player_previous, View.VISIBLE);
			if (supportBigNotifications) {
				notification.bigContentView.setViewVisibility(R.id.player_next, View.VISIBLE);
				notification.bigContentView.setViewVisibility(R.id.player_previous, View.VISIBLE);
				notification.bigContentView.setViewVisibility(R.id.player_progress_bar, View.GONE);
			}

			if (!AudioStreamingManager.getInstance(AudioStreamingService.this).isPlaying()) {
				notification.contentView.setViewVisibility(R.id.player_pause, View.GONE);
				notification.contentView.setViewVisibility(R.id.player_play, View.VISIBLE);
				if (supportBigNotifications) {
					notification.bigContentView.setViewVisibility(R.id.player_pause, View.GONE);
					notification.bigContentView.setViewVisibility(R.id.player_play, View.VISIBLE);
				}
			} else {
				notification.contentView.setViewVisibility(R.id.player_pause, View.VISIBLE);
				notification.contentView.setViewVisibility(R.id.player_play, View.GONE);
				if (supportBigNotifications) {
					notification.bigContentView.setViewVisibility(R.id.player_pause, View.VISIBLE);
					notification.bigContentView.setViewVisibility(R.id.player_play, View.GONE);
				}
			}

			notification.contentView.setTextViewText(R.id.player_song_name, songName);
			notification.contentView.setTextViewText(R.id.player_author_name, authorName);
			if (supportBigNotifications) {
				notification.bigContentView.setTextViewText(R.id.player_song_name, songName);
				notification.bigContentView.setTextViewText(R.id.player_author_name, authorName);
				//                notification.bigContentView.setTextViewText(R.id.player_albumname, albumName);
			}
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
			startForeground(5, notification);

			if (remoteControlClient != null) {
				RemoteControlClient.MetadataEditor metadataEditor = remoteControlClient.editMetadata(true);
				metadataEditor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, authorName);
				metadataEditor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, songName);
				if (albumArt != null) {
					metadataEditor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, albumArt);
				}
				metadataEditor.apply();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void setListeners(RemoteViews view) {
		try {
			PendingIntent pendingIntent =
				PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_PREVIOUS),
					PendingIntent.FLAG_UPDATE_CURRENT);
			view.setOnClickPendingIntent(R.id.player_previous, pendingIntent);
			pendingIntent =
				PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_CLOSE),
					PendingIntent.FLAG_UPDATE_CURRENT);
			view.setOnClickPendingIntent(R.id.player_close, pendingIntent);
			pendingIntent =
				PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_PAUSE),
					PendingIntent.FLAG_UPDATE_CURRENT);
			view.setOnClickPendingIntent(R.id.player_pause, pendingIntent);
			pendingIntent =
				PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_NEXT),
					PendingIntent.FLAG_UPDATE_CURRENT);
			view.setOnClickPendingIntent(R.id.player_next, pendingIntent);
			pendingIntent =
				PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_PLAY),
					PendingIntent.FLAG_UPDATE_CURRENT);
			view.setOnClickPendingIntent(R.id.player_play, pendingIntent);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (remoteControlClient != null) {
			RemoteControlClient.MetadataEditor metadataEditor = remoteControlClient.editMetadata(true);
			metadataEditor.clear();
			metadataEditor.apply();
			audioManager.unregisterRemoteControlClient(remoteControlClient);
		}
		try {
			TelephonyManager mgr = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
			if (mgr != null) {
				mgr.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
			}
		} catch (Exception e) {
			Log.e("tmessages", e.toString());
		}
		NotificationManager.getInstance()
			.removeObserver(this, NotificationManager.audioProgressDidChanged);
		NotificationManager.getInstance()
			.removeObserver(this, NotificationManager.audioPlayStateChanged);
	}

	@Override
	public void didReceivedNotification(int id, Object... args) {
		if (id == NotificationManager.setAnyPendingIntent) {
			PendingIntent pendingIntent = (PendingIntent) args[0];
			if (pendingIntent != null) {
				this.pendingIntent = pendingIntent;
			}
		} else if (id == NotificationManager.audioPlayStateChanged) {
			MediaMetaData mSongDetail =
				AudioStreamingManager.getInstance(AudioStreamingService.this).getCurrentAudio();
			if (mSongDetail != null) {
				createNotification(mSongDetail);
			} else {
				stopSelf();
			}
		}
	}

	@Override
	public void newSongLoaded(Object... args) {

	}

	class AsyncTaskRunner extends AsyncTask<String, String, Bitmap> {

		@Override
		protected Bitmap doInBackground(String... params) {

			try {
				URL url = new URL(params[0]);
				HttpURLConnection connection = null;
				connection = (HttpURLConnection) url.openConnection();
				connection.setDoInput(true);
				connection.connect();
				InputStream input = connection.getInputStream();
				Bitmap myBitmap = null;
				try {
					 myBitmap = BitmapFactory.decodeStream(input);
				} catch (Exception e) {
					e.printStackTrace();
				}
				return myBitmap;
			} catch (IOException e) {
				return null;
			}
		}

		@Override
		protected void onPostExecute(Bitmap result) {
			super.onPostExecute(result);
			// execution of result of Long time consuming operation
		}

		@Override
		protected void onPreExecute() {
		}

		@Override
		protected void onProgressUpdate(String... text) {

		}
	}
}
