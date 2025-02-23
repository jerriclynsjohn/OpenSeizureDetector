/*
  Pebble_sd - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  
  Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  
  You should have received a copy of the GNU General Public License
  along with pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/


package uk.org.openseizuredetector;

import java.util.Map;
import fi.iki.elonen.NanoHTTPD;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.preference.PreferenceManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;
import java.util.Timer;
import java.util.TimerTask;
import java.io.*;
import java.util.*;
import java.util.UUID;
import java.util.StringTokenizer;
import java.net.URL;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import android.text.format.Time;
import org.json.JSONObject;
import org.json.JSONArray;

import com.getpebble.android.kit.Constants;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;


/**
 * Based on example at:
 * http://stackoverflow.com/questions/14309256/using-nanohttpd-in-android
 * and 
 * http://developer.android.com/guide/components/services.html#ExtendingService
 */
public class SdServer extends Service
{
    private UUID SD_UUID = UUID.fromString("03930f26-377a-4a3d-aa3e-f3b19e421c9d");
    private int NSAMP = 512;   // Number of samples in fft input dataset.

    private int KEY_DATA_TYPE = 1;
    private int KEY_ALARMSTATE = 2;
    private int KEY_MAXVAL = 3;
    private int KEY_MAXFREQ = 4;
    private int KEY_SPECPOWER = 5;
    private int KEY_SETTINGS = 6;
    private int KEY_ALARM_FREQ_MIN =7;
    private int KEY_ALARM_FREQ_MAX =8;
    private int KEY_WARN_TIME = 9;
    private int KEY_ALARM_TIME = 10;
    private int KEY_ALARM_THRESH = 11;
    private int KEY_POS_MIN = 12;       // position of first data point in array
    private int KEY_POS_MAX = 13;       // position of last data point in array.
    private int KEY_SPEC_DATA = 14;     // Spectrum data
    private int KEY_ROIPOWER = 15;     
    private int KEY_NMIN = 16;
    private int KEY_NMAX = 17;
    private int KEY_ALARM_RATIO_THRESH = 18;
    private int KEY_BATTERY_PC = 19;

    // Values of the KEY_DATA_TYPE entry in a message
    private int DATA_TYPE_RESULTS = 1;   // Analysis Results
    private int DATA_TYPE_SETTINGS = 2;  // Settings
    private int DATA_TYPE_SPEC = 3;      // FFT Spectrum (or part of a spectrum)

    // Notification ID
    private int NOTIFICATION_ID = 1;

    private NotificationManager mNM;

    private WebServer webServer = null;
    private final static String TAG = "SdServer";
    private Looper mServiceLooper;
    public Time mPebbleStatusTime;
    private boolean mPebbleAppRunningCheck = false;
    private Timer statusTimer = null;
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
	private int mAppRestartTimeout = 10;  // Timeout before re-starting watch app (sec).
    private Timer settingsTimer = null;
    private Timer dataLogTimer = null;
    private HandlerThread thread;
    private WakeLock mWakeLock = null;
    public SdData sdData;
    private PebbleKit.PebbleDataReceiver msgDataHandler = null;
    private boolean mCancelAudible = false;
    private boolean mAudibleAlarm = false;
    private boolean mAudibleWarning = false;
    private boolean mAudibleFaultWarning = false;
    private boolean mSMSAlarm = false;
    private String[] mSMSNumbers;
    private String mSMSMsgStr = "default SMS Message";
    public Time mSMSTime = null;  // last time we sent an SMS Alarm (limited to one per minute)
    private boolean mLogAlarms = true;
    private boolean mLogData = false;
    private File mOutFile;

    private final IBinder mBinder = new SdBinder();

    /**
     * class to handle binding the MainApp activity to this service
     * so it can access sdData.
     */
    public class SdBinder extends Binder {
	SdServer getService() {
	    return SdServer.this;
	}
    }

    /**
     * Constructor for SdServer class - does not do much!
     */
    public SdServer() {
	super();
	sdData = new SdData();
	Log.v(TAG,"SdServer Created");
    }


    @Override
    public IBinder onBind(Intent intent) {
	Log.v(TAG,"sdServer.onBind()");
	return mBinder;
    }



    /**
     * onCreate() - called when services is created.  Starts message
     * handler process to listen for messages from other processes.
     */
    @Override
    public void onCreate() {
	Log.v(TAG,"onCreate()");

	// Create a wake lock, but don't use it until the service is started.
	PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
	mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
        "MyWakelockTag");
    }

    /**
     * onStartCommand - start the web server and the message loop for
     * communications with other processes.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
	Log.v(TAG,"onStartCommand() - SdServer service starting");
	
	// Update preferences.
	Log.v(TAG,"onStartCommand() - calling updatePrefs()");
	updatePrefs();
	
	// Display a notification icon in the status bar of the phone to
	// show the service is running.
	Log.v(TAG,"showing Notification");
	showNotification();

	// Record last time we sent an SMS so we can limit rate of SMS
	// sending to one per minute.
	mSMSTime = new Time(Time.getCurrentTimezone());


	// Start receiving data from the pebble watch
	startPebbleServer();

	// Start timer to check status of pebble regularly.
	mPebbleStatusTime = new Time(Time.getCurrentTimezone());
	//getPebbleStatus();
	if (statusTimer==null) {
	    Log.v(TAG,"onCreate(): starting status timer");
	    statusTimer = new Timer();
	    statusTimer.schedule(new TimerTask() {
		    @Override
		    public void run() {getPebbleStatus();}
		}, 0, 1000);	
	} else {
	    Log.v(TAG,"onCreate(): status timer already running.");
	}
	
	// Start timer to retrieve pebble settings regularly.
	getPebbleSdSettings();
	if (settingsTimer == null) {
	    Log.v(TAG,"onCreate(): starting settings timer");
	    settingsTimer = new Timer();
	    settingsTimer.schedule(new TimerTask() {
		    @Override
		    public void run() {getPebbleSdSettings();}
		}, 0, 1000*60);	
	} else {
	    Log.v(TAG,"onCreate(): settings timer already running.");
	}

	// Start timer to log data regularly..
	if (dataLogTimer == null) {
	    Log.v(TAG,"onCreate(): starting dataLog timer");
	    dataLogTimer = new Timer();
	    dataLogTimer.schedule(new TimerTask() {
		    @Override
		    public void run() {logData();}
		}, 0, 1000*60);	
	} else {
	    Log.v(TAG,"onCreate(): dataLog timer already running.");
	}


	// Start the web server
	startWebServer();

	// Apply the wake-lock to prevent CPU sleeping (very battery intensive!)
	if (mWakeLock!=null) {
	    mWakeLock.acquire();
	    Log.v(TAG,"Applied Wake Lock to prevent device sleeping");
	} else {
	    Log.d(TAG,"mmm...mWakeLock is null, so not aquiring lock.  This shouldn't happen!");
	}

	return START_STICKY;
    }

    @Override
    public void onDestroy() {
	Log.v(TAG,"onDestroy(): SdServer Service stopping");
	// release the wake lock to allow CPU to sleep and reduce
	// battery drain.
	if (mWakeLock!=null) {
	    mWakeLock.release();
	    Log.v(TAG,"Released Wake Lock to allow device to sleep.");
	} else {
	    Log.d(TAG, "mmm...mWakeLock is null, so not releasing lock.  This shouldn't happen!");
	}

	try {
	    // Stop the status timer
	    if (statusTimer!=null) {
		Log.v(TAG,"onDestroy(): cancelling status timer");
		statusTimer.cancel();
		statusTimer.purge();
		statusTimer = null;
	    }
	    // Stop the settings timer
	    if (settingsTimer!=null) {
		Log.v(TAG,"onDestroy(): cancelling settings timer");
		settingsTimer.cancel();
		settingsTimer.purge();
		settingsTimer = null;
	    }
	    // Cancel the notification.
	    Log.v(TAG,"onDestroy(): cancelling notification");
	    mNM.cancel(NOTIFICATION_ID);
	    // Stop web server
	    Log.v(TAG,"onDestroy(): stopping web server");
	    stopWebServer();
	    // Stop pebble message handler.
	    Log.v(TAG,"onDestroy(): stopping pebble server");
	    stopPebbleServer();
	    // stop this service.
	    Log.v(TAG,"onDestroy(): calling stopSelf()");
	    stopSelf();

	} catch(Exception e) {
	    Log.v(TAG,"Error in onDestroy() - "+e.toString());
	}
    }



    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
	Log.v(TAG,"showNotification()");
        CharSequence text = "OpenSeizureDetector Server Running";
        Notification notification = 
	   new Notification(R.drawable.star_of_life_24x24, text,
			     System.currentTimeMillis());
	PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);
        notification.setLatestEventInfo(this, "OpenSeizureDetector Server",
                      text, contentIntent);
	notification.flags |= Notification.FLAG_NO_CLEAR;
        mNM = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        mNM.notify(NOTIFICATION_ID, notification);
    }



    /* from http://stackoverflow.com/questions/12154940/how-to-make-a-beep-in-android */
    /**
     * beep for duration miliseconds, but only if mAudibleAlarm is set.
     */
    private void beep(int duration) {
	ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
	toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, duration); 
	Log.v(TAG,"beep()");
    }

    /*
     * beep, provided mAudibleAlarm is set
     */
	public void faultWarningBeep() {
		if (mCancelAudible) {
			Log.v(TAG, "faultWarningBeep() - CancelAudible Active - silent beep...");
		} else {
			if (mAudibleFaultWarning) {
				beep(10);
				Log.v(TAG, "faultWarningBeep()");
			} else {
				Log.v(TAG, "faultWarningBeep() - silent...");
			}
		}
	}



    /*
     * beep, provided mAudibleAlarm is set
     */
    public void alarmBeep() {
	if (mCancelAudible) {
	    Log.v(TAG,"alarmBeep() - CancelAudible Active - silent beep...");
	} else {
	    if (mAudibleAlarm) {
		beep(1000);
		Log.v(TAG,"alarmBeep()");
	    } else {
		Log.v(TAG,"alarmBeep() - silent...");
	    }
	}
    }

    /*
     * beep, provided mAudibleWarning is set
     */
    public void warningBeep() {
	if (mCancelAudible) {
	    Log.v(TAG,"warningBeep() - CancelAudible Active - silent beep...");
	} else {
	    if (mAudibleWarning) {
		beep(100);
		Log.v(TAG,"warningBeep()");
	    } else {
		Log.v(TAG,"warningBeep() - silent...");
	    }
	}
    }


    /**
     * Sends SMS Alarms to the telephone numbers specified in mSMSNumbers[]
     */
    public void sendSMSAlarm() {
	if (mSMSAlarm) {
	    Log.v(TAG,"sendSMSAlarm() - Sending to "+mSMSNumbers.length+" Numbers");
	    Time tnow = new Time(Time.getCurrentTimezone());
	    tnow.setToNow();
	    String dateStr = tnow.format("%Y-%m-%d %H-%M-%S");
	    SmsManager sm = SmsManager.getDefault();
	    for (int i=0;i<mSMSNumbers.length;i++) {
		Log.v(TAG,"sendSMSAlarm() - Sending to "+mSMSNumbers[i]);
		sm.sendTextMessage(mSMSNumbers[i], null, mSMSMsgStr+" - "+dateStr, null, null);
	    }
	} else {
	    Log.v(TAG,"sendSMSAlarm() - SMS Alarms Disabled - not doing anything!");
	    Toast toast = Toast.makeText(getApplicationContext(),
					 "SMS Alarms Disabled - not doing anything!",
					 Toast.LENGTH_SHORT);
	    toast.show();
	}
    }

    /**
     * Set this server to receive pebble data by registering it as
     * A PebbleDataReceiver
     */
	private void startPebbleServer() {
		Log.v(TAG, "StartPebbleServer()");
		final Handler handler = new Handler();
		msgDataHandler = new PebbleKit.PebbleDataReceiver(SD_UUID) {
			@Override
			public void receiveData(final Context context,
									final int transactionId,
									final PebbleDictionary data) {
				Log.v(TAG, "Received message from Pebble - data type="
						+ data.getUnsignedIntegerAsLong(KEY_DATA_TYPE));
				// If we ha ve a message, the app must be running
				mPebbleAppRunningCheck = true;
				PebbleKit.sendAckToPebble(context, transactionId);
				//Log.v(TAG,"Message is: "+data.toJsonString());
				if (data.getUnsignedIntegerAsLong(KEY_DATA_TYPE)
						== DATA_TYPE_RESULTS) {
					Log.v(TAG, "DATA_TYPE = Results");
					sdData.dataTime.setToNow();
					Log.v(TAG, "sdData.dataTime=" + sdData.dataTime);

					sdData.alarmState = data.getUnsignedIntegerAsLong(
							KEY_ALARMSTATE);
					sdData.maxVal = data.getUnsignedIntegerAsLong(KEY_MAXVAL);
					sdData.maxFreq = data.getUnsignedIntegerAsLong(KEY_MAXFREQ);
					sdData.specPower = data.getUnsignedIntegerAsLong(KEY_SPECPOWER);
					sdData.roiPower = data.getUnsignedIntegerAsLong(KEY_ROIPOWER);
					sdData.alarmPhrase = "Unknown";
					if (sdData.alarmState == 0) {
						sdData.alarmPhrase = "OK";
					}
					if (sdData.alarmState == 1) {
						sdData.alarmPhrase = "WARNING";
						if (mLogAlarms) {
							Log.v(TAG, "WARNING - Loggin to SD Card");
							writeAlarmToSD();
							logData();
						} else {
							Log.v(TAG, "WARNING");
						}
						warningBeep();
					}
					if (sdData.alarmState == 2) {
						sdData.alarmPhrase = "ALARM";
						if (mLogAlarms) {
							Log.v(TAG, "***ALARM*** - Loggin to SD Card");
							writeAlarmToSD();
							logData();
						} else {
							Log.v(TAG, "***ALARM***");
						}
						// Make alarm beep tone
						alarmBeep();
						// Send SMS Alarm.
						if (mSMSAlarm) {
							Time tnow = new Time(Time.getCurrentTimezone());
							tnow.setToNow();
							// limit SMS alarms to one per minute
							if ((tnow.toMillis(false)
									- mSMSTime.toMillis(false))
									> 60000) {
								sendSMSAlarm();
								mSMSTime = tnow;
							}
						}
					}


			// Read the data that has been sent, and convert it into
			// an integer array.
			byte[] byteArr = data.getBytes(KEY_SPEC_DATA);
			IntBuffer intBuf = ByteBuffer.wrap(byteArr)
			    .order(ByteOrder.LITTLE_ENDIAN)
			    .asIntBuffer();
			int[] intArray = new int[intBuf.remaining()];
			intBuf.get(intArray);
			for (int i=0;i<intArray.length;i++) {
			    sdData.simpleSpec[i] = intArray[i];
			}


		    }
		    if (data.getUnsignedIntegerAsLong(KEY_DATA_TYPE)
			==DATA_TYPE_SETTINGS) {
			Log.v(TAG,"DATA_TYPE = Settings");
			sdData.alarmFreqMin = data.getUnsignedIntegerAsLong(KEY_ALARM_FREQ_MIN);
			sdData.alarmFreqMax = data.getUnsignedIntegerAsLong(KEY_ALARM_FREQ_MAX);
			sdData.nMin = data.getUnsignedIntegerAsLong(KEY_NMIN);
			sdData.nMax = data.getUnsignedIntegerAsLong(KEY_NMAX);
			sdData.warnTime = data.getUnsignedIntegerAsLong(KEY_WARN_TIME);
			sdData.alarmTime = data.getUnsignedIntegerAsLong(KEY_ALARM_TIME);
			sdData.alarmThresh = data.getUnsignedIntegerAsLong(KEY_ALARM_THRESH);
			sdData.alarmRatioThresh = data.getUnsignedIntegerAsLong(KEY_ALARM_RATIO_THRESH);
			sdData.batteryPc = data.getUnsignedIntegerAsLong(KEY_BATTERY_PC);
			sdData.haveSettings = true;
		    }	
		}
	    };
	PebbleKit.registerReceivedDataHandler(this,msgDataHandler);
    }

    /**
     * De-register this server from receiving pebble data
     */
    public void stopPebbleServer() {
	Log.v(TAG,"stopPebbleServer(): Stopping Pebble Server");
	Log.v(TAG,"stopPebbleServer(): msgDataHandler = "+msgDataHandler.toString());
	try {
	    unregisterReceiver(msgDataHandler);
	} catch (Exception e) {
	    Log.v(TAG,"stopPebbleServer() - error "+e.toString());
	}
    }
    /**
     * Attempt to start the pebble_sd watch app on the pebble watch.
     */
    public void startWatchApp() {
	PebbleKit.startAppOnPebble(getApplicationContext(),
				   SD_UUID);

    }

    /**
     * stop the pebble_sd watch app on the pebble watch.
     */
    public void stopWatchApp() {
	PebbleKit.closeAppOnPebble(getApplicationContext(),
				   SD_UUID);
    }


    /**
     * Start the web server (on port 8080)
     */
    protected void startWebServer() {
	Log.v(TAG,"startWebServer()");
	if (webServer == null) {
	    webServer = new WebServer();
	    try {
		webServer.start();
	    } catch(IOException ioe) {
		Log.w(TAG, "startWebServer(): Error: "+ioe.toString());
	    }
	    Log.w(TAG, "startWebServer(): Web server initialized.");
	} else {
	    Log.v(TAG, "startWebServer(): server already running???");
	}
    }

    /**
     * Stop the web server - FIXME - doesn't seem to do anything!
     */
    protected void stopWebServer() {
	Log.v(TAG,"stopWebServer()");
	if (webServer!=null) {
	    webServer.stop();
	    if (webServer.isAlive()) {
		Log.v(TAG,"stopWebServer() - server still alive???");
	    } else {
		Log.v(TAG,"stopWebServer() - server died ok");
	    }
	    webServer = null;
	}
    }

    /**
     * Log data to SD card if mLogData is set in preferences.
     */
    public void logData() {
	if (mLogData) {
	    Log.v(TAG,"logData() - writing data to SD Card");
	    writeToSD();
	}
    }

    /** 
     * Checks the status of the connection to the pebble watch,
     * and sets class variables for use by other functions.
     * If the watch app is not running, it attempts to re-start it.
     */
	public void getPebbleStatus() {
		Time tnow = new Time(Time.getCurrentTimezone());
		long tdiff;
		tnow.setToNow();
		tdiff = (tnow.toMillis(false) - mPebbleStatusTime.toMillis(false));
		// Check we are actually connected to the pebble.
		sdData.pebbleConnected = PebbleKit.isWatchConnected(this);
		// And is the pebble_sd app running?
		// set mPebbleAppRunningCheck has been false for more than 10 seconds
		// the app is not talking to us
		// mPebbleAppRunningCheck is set to true in the receiveData handler.
		if (!mPebbleAppRunningCheck &&
				(tdiff > mAppRestartTimeout * 1000)) {
			Log.v(TAG, "getPebbleStatus() - tdiff = " + tdiff);
			sdData.pebbleAppRunning = false;
			Log.v(TAG, "getPebbleStatus() - Pebble App Not Running - Attempting to Re-Start");
			startWatchApp();
			getPebbleSdSettings();
			// Only make audible warning beep if we have not received data for more than mFaultTimerPeriod seconds.
			if ((tdiff > mFaultTimerPeriod * 1000)
					&& (mAudibleFaultWarning)
					) {
				faultWarningBeep();
			} else {
				Log.v(TAG, "getPebbleStatus() - Waiting for mFaultTimerPeriod before issuing audible warning...");
			}
		} else {
			sdData.pebbleAppRunning = true;
		}

		// if we have confirmation that the app is running, reset the
		// status time to now and initiate another check.
		if (mPebbleAppRunningCheck) {
			mPebbleAppRunningCheck = false;
			mPebbleStatusTime.setToNow();
		}

		if (!sdData.haveSettings) {
			Log.v(TAG, "getPebbleStatus() - no settings received yet - requesting");
			getPebbleSdSettings();
		}
	}

    /**
     * Request Pebble App to send us its latest settings.
     * Will be received as a message by the receiveData handler
     */
    public void getPebbleSdSettings() {
	Log.v(TAG,"getPebbleSdSettings() - requesting settings from pebble");
	PebbleDictionary data = new PebbleDictionary();
	data.addUint8(KEY_SETTINGS, (byte)1);
	PebbleKit.sendDataToPebble(
				   getApplicationContext(), 
				   SD_UUID, 
				   data);     
    }


    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/prefs.xml
     */
    public void updatePrefs() {
	Log.v(TAG,"updatePrefs()");
	SharedPreferences SP = PreferenceManager
	    .getDefaultSharedPreferences(getBaseContext());
	try {
	    mAudibleFaultWarning = SP.getBoolean("AudibleFaultWarning",true);
	    Log.v(TAG,"updatePrefs() - mAuidbleFaultWarning = "+mAudibleFaultWarning);
	    mAudibleAlarm = SP.getBoolean("AudibleAlarm",true);
	    Log.v(TAG,"updatePrefs() - mAuidbleAlarm = "+mAudibleAlarm);
	    mAudibleWarning = SP.getBoolean("AudibleWarning",true);
	    Log.v(TAG,"updatePrefs() - mAuidbleWarning = "+mAudibleWarning);
	    mSMSAlarm = SP.getBoolean("SMSAlarm",false);
	    Log.v(TAG,"updatePrefs() - mSMSAlarm = "+mSMSAlarm);
	    String SMSNumberStr = SP.getString("SMSNumbers","");
	    mSMSNumbers = SMSNumberStr.split(",");
	    mSMSMsgStr = SP.getString("SMSMsg","Seizure Detected!!!");
	    Log.v(TAG,"updatePrefs() - SMSNumberStr = "+SMSNumberStr);
	    Log.v(TAG,"updatePrefs() - mSMSNumbers = "+mSMSNumbers);
	    mLogAlarms = SP.getBoolean("LogAlarms",true);
	    Log.v(TAG,"updatePrefs() - mLogAlarms = "+mLogAlarms);
	    mLogData = SP.getBoolean("LogData",false);
	    Log.v(TAG,"updatePrefs() - mLogData = "+mLogData);

		// Parse the AppRestartTimeout period setting.
		try {
			String appRestartTimeoutStr = SP.getString("AppRestartTimeout", "10");
			mAppRestartTimeout = Integer.parseInt(appRestartTimeoutStr);
			Log.v(TAG, "onStart() - mAppRestartTimeout = " + mAppRestartTimeout);
		} catch (Exception ex) {
			Log.v(TAG, "onStart() - Problem with AppRestartTimeout preference!");
			Toast toast = Toast.makeText(getApplicationContext(), "Problem Parsing AppRestartTimeout Preference", Toast.LENGTH_SHORT);
			toast.show();
		}


	    // Parse the FaultTimer period setting.
		try {
			String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
			mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
			Log.v(TAG, "onStart() - mFaultTimerPeriod = " + mFaultTimerPeriod);
		} catch (Exception ex) {
			Log.v(TAG, "onStart() - Problem with FaultTimerPeriod preference!");
			Toast toast = Toast.makeText(getApplicationContext(), "Problem Parsing FaultTimerPeriod Preference", Toast.LENGTH_SHORT);
			toast.show();
		}


	    // Watch Settings 
	    PebbleDictionary setDict = new PebbleDictionary();
	    short intVal;
	    String prefStr;
	    prefStr = SP.getString("AlarmFreqMin","5");
	    intVal = (short)Integer.parseInt(prefStr);
	    Log.v(TAG,"updatePrefs() AlarmFreqMin = "+intVal);
	    setDict.addInt16(KEY_ALARM_FREQ_MIN,intVal);
	    
	    prefStr = SP.getString("AlarmFreqMax","10");
	    intVal = (short)Integer.parseInt(prefStr);
	    Log.v(TAG,"updatePrefs() AlarmFreqMax = "+intVal);
	    setDict.addUint16(KEY_ALARM_FREQ_MAX,(short)intVal);

	    prefStr = SP.getString("WarnTime","5");
	    intVal = (short)Integer.parseInt(prefStr);
	    Log.v(TAG,"updatePrefs() WarnTime = "+intVal);
	    setDict.addUint16(KEY_WARN_TIME,(short)intVal);
	    
	    prefStr = SP.getString("AlarmTime","10");
	    intVal = (short)Integer.parseInt(prefStr);
	    Log.v(TAG,"updatePrefs() AlarmTime = "+intVal);
	    setDict.addUint16(KEY_ALARM_TIME,(short)intVal);
	    
	    prefStr = SP.getString("AlarmThresh","100");
	    intVal = (short)Integer.parseInt(prefStr);
	    Log.v(TAG,"updatePrefs() AlarmThresh = "+intVal);
	    setDict.addUint16(KEY_ALARM_THRESH,(short)intVal);
	    
	    prefStr = SP.getString("AlarmRatioThresh","30");
	    intVal = (short)Integer.parseInt(prefStr);
	    Log.v(TAG,"updatePrefs() AlarmRatioThresh = "+intVal);
	    setDict.addUint16(KEY_ALARM_RATIO_THRESH,(short)intVal);
	    // Send to Pebble
	    Log.v(TAG,"updatePrefs() - setDict = "+setDict.toJsonString());
	    PebbleKit.sendDataToPebble(getApplicationContext(), SD_UUID, setDict);
	} catch (Exception ex) {
	    Log.v(TAG,"updatePrefs() - Problem parsing preferences!");
	    Toast toast = Toast.makeText(getApplicationContext(),"Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!",Toast.LENGTH_SHORT);
	    toast.show();
	}
    }


    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
	String state = Environment.getExternalStorageState();
	if (Environment.MEDIA_MOUNTED.equals(state)) {
	    return true;
	}
	return false;
    }

    public File getDataStorageDir() {
	// Get the directory for the user's public pictures directory. 
	File file = 
	    new File(Environment.getExternalStorageDirectory()
		     ,"OpenSeizureDetector");
	if (!file.mkdirs()) {
	    Log.e(TAG, "Directory not created");
	}
	return file;
    }

    /**
     * Write data to SD card alarm log
     */
    public void writeAlarmToSD() {
	writeToSD(true);
    }

    /**
     * Write to data log file on SD Card
     */
    public void writeToSD() {
	writeToSD(false);
    }
    
    /**
     * Write data to SD card - writes to data log file unless alarm=true,
     * in which case writes to alarm log file.
     */
    public void writeToSD(boolean alarm) {
	Log.v(TAG,"writeToSD("+alarm+")");
	Time tnow = new Time(Time.getCurrentTimezone());
	tnow.setToNow();
	String dateStr = tnow.format("%Y-%m-%d");

	// Select filename depending on 'alarm' parameter.
	String fname;
	if (alarm) 
	    fname = "AlarmLog";
	else
	    fname = "DataLog";

	fname = fname+"_"+dateStr+".txt";
	// Open output directory on SD Card.
	if (isExternalStorageWritable()) {
	    try {
		FileWriter of = new FileWriter(getDataStorageDir().toString() 
					       + "/" + fname, true);
		if (sdData!=null) {
		    Log.v(TAG,"writing sdData.toString()");
		    of.append(sdData.toString()+"\n");
		}
		of.close();
	    } catch(Exception ex) {
		Log.e(TAG,"writeAlarmToSD - error "+ex.toString());
	    }    
	} else {
	    Log.e(TAG,"ERROR - Can not Write to External Folder");
	}	
    }

    /**
     * Class describing the seizure detector web server - appears on port
     * 8080.
     */
    private class WebServer extends NanoHTTPD {
	private String TAG = "WebServer";
        public WebServer()
        {
	    // Set the port to listen on (8080)
            super(8080);
        }

        @Override
        public Response serve(String uri, Method method, 
                              Map<String, String> header,
                              Map<String, String> parameters,
                              Map<String, String> files) {
	    Log.v(TAG,"WebServer.serve() - uri="+uri+" Method="+method.toString());
	    String answer = "Error - you should not see this message! - Something wrong in WebServer.serve()";

	    Iterator it = parameters.keySet().iterator();
	    while (it.hasNext()) {
		Object key = it.next();
		Object value = parameters.get(key);
		//Log.v(TAG,"Request parameters - key="+key+" value="+value);
	    }

	    if (uri.equals("/")) uri = "/index.html";
	    switch(uri) {
	    case "/data":
		//Log.v(TAG,"WebServer.serve() - Returning data");
		try {
		    //JSONObject jsonObj = new JSONObject();
		    //jsonObj.put("Time",mPebbleStatusTime.format("%H:%M:%S"));
		    //jsonObj.put("alarmState",sdData.alarmState);
		    //jsonObj.put("alarmPhrase",sdData.alarmPhrase);
		    //jsonObj.put("maxVal",sdData.maxVal);
		    //jsonObj.put("maxFreq",sdData.maxFreq);
		    //jsonObj.put("specPower",sdData.specPower);
		    //jsonObj.put("roiPower",sdData.roiPower);
		    //jsonObj.put("pebCon",mPebbleConnected);
		    //jsonObj.put("pebAppRun",mPebbleAppRunning);
		    answer = sdData.toString();
		} catch (Exception ex) {
		    Log.v(TAG,"Error Creating Data Object - "+ex.toString());
		    answer = "Error Creating Data Object";
		}
		break;

	    case "/settings":
		//Log.v(TAG,"WebServer.serve() - Returning settings");
		try {
		    JSONObject jsonObj = new JSONObject();
		    jsonObj.put("alarmFreqMin",sdData.alarmFreqMin);
		    jsonObj.put("alarmFreqMax",sdData.alarmFreqMax);
		    jsonObj.put("nMin",sdData.nMin);
		    jsonObj.put("nMax",sdData.nMax);
		    jsonObj.put("warnTime",sdData.warnTime);
		    jsonObj.put("alarmTime",sdData.alarmTime);
		    jsonObj.put("alarmThresh",sdData.alarmThresh);
		    jsonObj.put("alarmRatioThresh",sdData.alarmRatioThresh);
		    jsonObj.put("batteryPc",sdData.batteryPc);
		    answer = jsonObj.toString();
		} catch (Exception ex) {
		    Log.v(TAG,"Error Creating Data Object - "+ex.toString());
		    answer = "Error Creating Data Object";
		}
		break;

	    case "/spectrum":
		Log.v(TAG,"WebServer.serve() - Returning spectrum - 1");
		try {
		    JSONObject jsonObj = new JSONObject();
		    Log.v(TAG,"WebServer.serve() - Returning spectrum - 2");
		    // Initialised it this way because one phone was ok with JSONArray(sdData.simpleSpec), and the other crashed...
		    JSONArray arr = new JSONArray();
		    for (int i=0;i<sdData.simpleSpec.length;i++) {
			arr.put(sdData.simpleSpec[i]);
		    }

		    Log.v(TAG,"WebServer.serve() - Returning spectrum - 3");
		    jsonObj.put("simpleSpec",arr);
		    Log.v(TAG,"WebServer.serve() - Returning spectrum - 4");
		    answer = jsonObj.toString();
		    Log.v(TAG,"WebServer.serve() - Returning spectrum - 5"+answer);
		} catch (Exception ex) {
		    Log.v(TAG,"Error Creating Data Object - "+ex.toString());
		    answer = "Error Creating Data Object";
		}
		break;

	    default:
		if (uri.startsWith("/index.html") ||
		    uri.startsWith("/favicon.ico") ||
		    uri.startsWith("/js/") ||
		    uri.startsWith("/css/") ||
		    uri.startsWith("/img/")) {
		    //Log.v(TAG,"Serving File");
		    return serveFile(uri);
		} 
		else if (uri.startsWith("/logs")) {
		    Log.v(TAG,"WebServer.serve() - serving data logs - uri="+uri);
		    NanoHTTPD.Response resp = serveLogFile(uri);
		    Log.v(TAG,"WebServer.serve() - response = "+resp.toString());
		    return resp;
		} else {
		    Log.v(TAG,"WebServer.serve() - Unknown uri -"+
			  uri);
		    answer = "Unknown URI: ";
		}
	    }

            return new NanoHTTPD.Response(answer);
        }
    

    

	/**
	 * Return a file from the external storage folder
	 */
	NanoHTTPD.Response serveLogFile(String uri) {
	    NanoHTTPD.Response res;
	    InputStream ip = null;
	    String uripart;
	    Log.v(TAG,"serveLogFile("+uri+")");
	    try {
		if (ip!=null) ip.close();
		String storageDir = getDataStorageDir().toString();
		StringTokenizer uriParts = new StringTokenizer(uri,"/");
		Log.v(TAG,"serveExternalFile - number of tokens = "+uriParts.countTokens());	    
		while (uriParts.hasMoreTokens()) {
		    uripart = uriParts.nextToken();
		    Log.v(TAG,"uripart="+uripart);
		}

		// If we have only given a "/logs" URI, return a list of
		// available files.
		// Re-start the StringTokenizer from the start.
		uriParts = new StringTokenizer(uri,"/");
		Log.v(TAG,"serveExternalFile - number of tokens = "
		      +uriParts.countTokens());	    
		if (uriParts.countTokens()==1) {
		    Log.v(TAG,"Returning list of files");
		    
		    File dirs = getDataStorageDir();
		    try {
			JSONObject jsonObj = new JSONObject();
			if (dirs.exists()) {
			    String[] fileList = dirs.list();
			    JSONArray arr = new JSONArray();
			    for (int i=0;i<fileList.length;i++)
				arr.put(fileList[i]);
			    jsonObj.put("logFileList",arr);
			}
			res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
						     "text/html",jsonObj.toString());
		    } catch(Exception ex) {
			res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
						     "text/html","ERROR - "+ex.toString());
		    }
		    return res;
		}

		uripart = uriParts.nextToken();  // This will just be /logs
		uripart = uriParts.nextToken();  // this is the requested file.
		String fname = storageDir + "/"+uripart;
		Log.v(TAG,"serveLogFile - uri="+uri+", fname="+fname);
		ip = new FileInputStream(fname);
		String mimeStr = "text/html";
		res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
					     mimeStr,ip);
		res.addHeader("Content-Length", "" + ip.available());
	    } catch (IOException ex) {
		Log.v(TAG,"serveLogFile(): Error Opening File - "+ex.toString());
		res = new NanoHTTPD.Response("serveLogFile(): Error Opening file "+uri);
	    } 
	    return(res);
	}
	
	/**
	 * Return a file from the apps /assets folder
	 */
	NanoHTTPD.Response serveFile(String uri) {
	    NanoHTTPD.Response res;
	    InputStream ip = null;
	    try {
		if (ip!=null) ip.close();
		String assetPath = "www";
		String fname = assetPath+uri;
		//Log.v(TAG,"serveFile - uri="+uri+", fname="+fname);
		AssetManager assetManager = getResources().getAssets();
		ip = assetManager.open(fname);
		String mimeStr = "text/html";
		res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
					     mimeStr,ip);
		res.addHeader("Content-Length", "" + ip.available());
	    } catch (IOException ex) {
		Log.v(TAG,"serveFile(): Error Opening File - "+ex.toString());
		res = new NanoHTTPD.Response("serveFile(): Error Opening file "+uri);
	    } 
	    return(res);
	}
    }
}
