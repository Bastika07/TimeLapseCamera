/*
 * Spartan Time Lapse Recorder - Minimalistic android time lapse recording app
 * Copyright (C) 2014  Andreas Rohner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.andreasrohner.spartantimelapserec.recorder;

import java.io.File;
import java.io.IOException;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.PowerManager.WakeLock;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.Log;
import at.andreasrohner.spartantimelapserec.StoragePermissionHelper;
import at.andreasrohner.spartantimelapserec.data.RecSettings;
import at.andreasrohner.spartantimelapserec.sensor.MuteShutter;
import at.andreasrohner.spartantimelapserec.sensor.OrientationSensor;

public abstract class Recorder {
	protected Context mContext;
	protected RecSettings mSettings;
	protected Camera mCamera;
	protected boolean mCanDisableShutterSound;
	protected Handler mHandler;
	protected int mInitDelay;
	private OrientationSensor mOrientation;
	protected MuteShutter mMute;
	private File mOutputDir;
	private int mFileIndex;

	public static Recorder getInstance(RecSettings settings,
									   Context context, Handler handler,
									   WakeLock wakeLock) {
		Recorder recorder;

		switch (settings.getRecMode()) {
			case VIDEO_TIME_LAPSE:
				recorder = new VideoTimeLapseRecorder(settings,
						context, handler);
				break;
			case IMAGE_TIME_LAPSE:
				if (settings.shouldUsePowerSaveMode()) {
					recorder = new PowerSavingImageRecorder(settings,
							context, handler, wakeLock);
				} else {
					recorder = new ImageRecorder(settings,  context,
							handler);
				}
				break;
			default:
				recorder = new VideoRecorder(settings,  context,
						handler);
				break;
		}

		return recorder;
	}

	public Recorder(RecSettings settings, 			Context context, Handler handler) {
		mContext = context;

		mOrientation = new OrientationSensor(context);
		mOrientation.enable();

		mSettings = settings;
		mHandler = handler;

		CameraInfo info = new CameraInfo();
		Camera.getCameraInfo(mSettings.getCameraId(), info);
		mCanDisableShutterSound = info.canDisableShutterSound;

		mMute = new MuteShutter(context);

		String dateSubdir = settings.getProjectName() + "/"
				+ DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
				+ "/";

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			// API 29+: files are written via MediaStore directly into Pictures —
			// no local directory needs to be created.
			mOutputDir = null;
		} else {
			// API < 29: write directly to the filesystem (WRITE_EXTERNAL_STORAGE required).
			File outputDir = new File(settings.getProjectPath() + "/" + dateSubdir);

			if (!outputDir.exists() && !outputDir.mkdirs()) {
				boolean permMissing = !StoragePermissionHelper.isGranted(context);
				if (permMissing) {
					Log.w("TimeLapseCamera",
							"Storage permission not granted — falling back to app-private storage.");
				} else {
					Log.w("TimeLapseCamera", "Could not create preferred directory: "
							+ outputDir + " — falling back to app-private storage");
				}

				File fallback = new File(context.getExternalFilesDir(
						Environment.DIRECTORY_PICTURES), dateSubdir);

				if (!fallback.exists() && !fallback.mkdirs()) {
					Log.e("TimeLapseCamera", "Failed to make fallback directory: " + fallback);
					mOutputDir = null;
					return;
				}
				Log.i("TimeLapseCamera", "Using fallback directory: " + fallback);
				outputDir = fallback;
			}
			mOutputDir = outputDir;
		}

		mInitDelay = settings.getInitDelay();
	}

	protected void releaseCamera() {
		if (mCamera == null)
			return;

		try {
			mCamera.reconnect();
			mCamera.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
		mCamera = null;
	}

	protected void handleError(String tag, String msg) {
		if (mHandler != null) {
			Message m = new Message();
			Bundle b = new Bundle();
			b.putString("status", "error");
			b.putString("tag", tag);
			b.putString("msg", msg);
			m.setData(b);
			m.setTarget(mHandler);
			mHandler.sendMessage(m);
			mHandler = null;
		}
	}

	protected void success() {
		if (mHandler != null) {
			Message m = new Message();
			Bundle b = new Bundle();
			b.putString("status", "success");
			m.setData(b);
			m.setTarget(mHandler);
			mHandler.sendMessage(m);
		}
	}

	protected void disableOrientationSensor() {
		if (mOrientation != null)
			mOrientation.disable();
	}

	protected void enableOrientationSensor() {
		if (mOrientation != null)
			mOrientation.enable();
	}

	public void stop() {
		disableOrientationSensor();
		mOrientation = null;

		releaseCamera();
		unmuteShutter();

		mHandler = null;
		mContext = null;
		mMute = null;
	}

	protected abstract void prepareRecord() throws Exception;

	protected abstract void doRecord() throws Exception;

	public void start() {
		long timeDiff = SystemClock.elapsedRealtime();
		Runnable r = new Runnable() {
			@Override
			public void run() {
				try {
					doRecord();
				} catch (Exception e) {
					e.printStackTrace();
					handleError(Recorder.class.getSimpleName(), e.getMessage());
				}
			}
		};

		if (mHandler == null || mContext == null) {
			return;
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && mOutputDir == null) {
			handleError(getClass().getSimpleName(), "Output directory could not be created");
			return;
		}

		enableOrientationSensor();

		try {
			prepareRecord();
		} catch (Exception e) {
			e.printStackTrace();
			handleError(getClass().getSimpleName(), e.getMessage());
			return;
		}

		timeDiff = SystemClock.elapsedRealtime() - timeDiff;
		timeDiff = mInitDelay - timeDiff;
		if (timeDiff <= 0)
			mHandler.post(r);
		else
			mHandler.postDelayed(r, timeDiff);

		mInitDelay = 0;
	}

	public File getOutputDir() {
		return mOutputDir;
	}

	protected File getOutputFile(String ext) throws IOException {
		if (!mOutputDir.isDirectory())
			throw new IOException("Could not open directory: " + mOutputDir);

		File outFile;
		do {
			outFile = new File(mOutputDir, mSettings.getProjectName()
					+ mFileIndex + "." + ext);
			mFileIndex++;
		} while (outFile.isFile());

		return outFile;
	}

	protected Uri createOutputUri(String mimeType, String ext) throws IOException {
		String date = (String) DateFormat.format("yyyy-MM-dd", System.currentTimeMillis());
		String baseDir = mimeType.startsWith("video/")
				? Environment.DIRECTORY_MOVIES
				: Environment.DIRECTORY_PICTURES;
		String relativePath = baseDir + "/" + mSettings.getProjectName() + "/" + date;
		String filename = mSettings.getProjectName() + mFileIndex + "." + ext;
		mFileIndex++;

		ContentValues values = new ContentValues();
		values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
		values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
		values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);

		ContentResolver resolver = mContext.getContentResolver();
		Uri collection = mimeType.startsWith("video/")
				? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
				: MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

		Uri uri = resolver.insert(collection, values);
		if (uri == null)
			throw new IOException("MediaStore insert failed for: " + filename);
		return uri;
	}

	protected int getCameraRotation(int cameraId) {
		if (mOrientation != null)
			return mOrientation.getCameraRotation(cameraId);
		return 0;
	}


	protected void muteShutter() {
		if (mSettings != null && mSettings.isMuteShutter()) {
			if (mCanDisableShutterSound) {
				// don't merge with upper if (to prevent elseif-branch if
				// mCamera == null)
				if (mCamera != null)
					mCamera.enableShutterSound(false);
			} else if (mMute != null) {
				mMute.muteShutter();
			}
		}

	}

	protected void unmuteShutter() {
		if (mSettings != null && mSettings.isMuteShutter()) {
			if (mCanDisableShutterSound) {
				// don't merge with upper if (to prevent elseif-branch if
				// mCamera == null)
				if (mCamera != null)
					mCamera.enableShutterSound(true);
			} else if (mMute != null) {
				mMute.unmuteShutter();
			}
		}
	}
}
