package io.r.a.dio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.RemoteViews;

public class RadioService extends Service implements OnPreparedListener,
		MediaPlayer.OnErrorListener {
	private final IBinder binder = new LocalBinder();
	private Messenger messenger;
	private boolean activityConnected;
	private Messenger activityMessenger;
	private ApiPacket currentApiPacket;
	private NotificationHandler notificationManager;
	private Timer apiDataTimer;
	private Timer widgetTimer;
	MediaPlayer radioPlayer;
	public static boolean serviceStarted = false;
	public static RadioService service;
	AppWidgetManager widgetManager;
	private int progress;
	private int length;

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onCreate() {
		notificationManager = new NotificationHandler(this);
		widgetManager = AppWidgetManager.getInstance(this);
		currentApiPacket = new ApiPacket();
		messenger = new Messenger(new Handler() {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case ApiUtil.ACTIVITYCONNECTED:
					activityConnected = true;
					activityMessenger = msg.replyTo;
					break;
				case ApiUtil.ACTIVITYDISCONNECTED:
					activityConnected = false;
					break;
				}
			}
		});
		this.startForeground(NotificationHandler.CONSTANTNOTIFICATION,
				notificationManager.constantNotification());
		apiDataTimer = new Timer();
		apiDataTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				updateApiData();
			}
		}, 0, 10000);
		widgetTimer = new Timer();
		widgetTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				RemoteViews view = new RemoteViews(getPackageName(),
						R.layout.widget_layout);
				progress++;
				view.setTextViewText(R.id.widget_NowPlaying, currentApiPacket.np);
				view.setProgressBar(R.id.widget_ProgressBar, length, progress,
						false);
				view.setTextViewText(R.id.widget_SongLength,
						formatSongLength(progress, length));

				// Push update for this widget to the home screen
				ComponentName thisWidget = new ComponentName(
						getApplicationContext(), RadioWidgetProvider.class);
				AppWidgetManager manager = AppWidgetManager
						.getInstance(getApplicationContext());
				manager.updateAppWidget(thisWidget, view);
			}
		}, 0, 1000);
		radioPlayer = new MediaPlayer();
		radioPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		radioPlayer.setOnPreparedListener(this);
		try {
			radioPlayer.setDataSource(getString(R.string.streamURL));
		} catch (Exception e) {
			e.printStackTrace();
		}
		radioPlayer.prepareAsync();
		service = this;
	}

	public void onPrepared(MediaPlayer mp) {
		radioPlayer.start();
	}

	public boolean onError(MediaPlayer mp, int what, int extra) {
		return true;
	}

	private String formatSongLength(int progress, int length) {
		StringBuilder sb = new StringBuilder();

		int progMins = progress / 60;
		int progSecs = progress % 60;
		if (progMins < 10)
			sb.append("0");
		sb.append(progMins);
		sb.append(":");
		if (progSecs < 10)
			sb.append("0");
		sb.append(progSecs);

		sb.append(" / ");

		int lenMins = length / 60;
		int lenSecs = length % 60;
		if (lenMins < 10)
			sb.append("0");
		sb.append(lenMins);
		sb.append(":");
		if (lenSecs < 10)
			sb.append("0");
		sb.append(lenSecs);

		return sb.toString();
	}

	public void stopPlayer() {
		radioPlayer.reset();
	}

	// call
	public void restartPlayer() {
		radioPlayer.reset();
		try {
			radioPlayer.setDataSource(getString(R.string.streamURL));
		} catch (Exception e) {
			e.printStackTrace();
		}
		radioPlayer.prepareAsync();

	}

	public Messenger getMessenger() {
		return this.messenger;
	}

	public class LocalBinder extends Binder {
		public RadioService getService() {
			return RadioService.this;
		}
	}

	@Override
	public boolean onUnbind(Intent intent) {
		activityConnected = false;
		return super.onUnbind(intent);
	}

	public void updateApiData() {
		ApiDataGetter apiGetter = new ApiDataGetter();
		apiGetter.execute();
	}

	private class ApiDataGetter extends AsyncTask<Void, Void, Void> {
		ApiPacket resultPacket;

		@Override
		protected Void doInBackground(Void... params) {
			resultPacket = new ApiPacket();
			try {
				URL apiURl = new URL(getString(R.string.mainApiURL));
				BufferedReader in = new BufferedReader(new InputStreamReader(
						apiURl.openStream()));
				String inputLine = in.readLine();
				in.close();
				resultPacket = ApiUtil.parseJSON(inputLine);
				progress = (int) (resultPacket.cur - resultPacket.start);
				length = (int) (resultPacket.end - resultPacket.start);
				String[] songParts = resultPacket.np.split(" - ");
				if (songParts.length == 2) {
					resultPacket.artistName = songParts[0];
					resultPacket.songName = songParts[1];
				} else {
					resultPacket.songName = songParts[0];
					resultPacket.artistName = "-";
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			currentApiPacket = resultPacket;
			Message m = Message.obtain();
			m.what = ApiUtil.NPUPDATE;
			m.obj = currentApiPacket;
			if (activityConnected) {
				try {
					activityMessenger.send(m);
				} catch (RemoteException e) {
					// Whatever...
				}
			}
			notificationManager.updateNotificationWithInfo(currentApiPacket);
		}

	}

	public void updateNotificationImage(Bitmap image) {
		notificationManager.updateNotificationImage(currentApiPacket, image);
	}

	@Override
	public void onDestroy() {
		radioPlayer.release();
	}

}
