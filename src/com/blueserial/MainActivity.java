/*
 * Released under MIT License http://opensource.org/licenses/MIT
 * Copyright (c) 2013 Plasty Grove
 * Refer to file LICENSE or URL above for full text 
 */

package com.blueserial;

import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Observable;
import java.util.Observer;
import java.util.UUID;

import com.androidplot.Plot;
import com.androidplot.series.XYSeries;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYStepMode;
import com.EMGControl.R;
import com.androidplot.xy.SimpleXYSeries;
import com.google.common.primitives.Doubles;

import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

	// redraws a plot whenever an update is received:
	private class MyPlotUpdater implements Observer {
		Plot plot;

		public MyPlotUpdater(Plot plot) {
			this.plot = plot;
		}

		@Override
		public void update(Observable o, Object arg) {
			plot.redraw();
		}
	}

	private static final String TAG = "BlueTest5-MainActivity";
	private int mMaxChars = 50000;// Default
	private UUID mDeviceUUID;
	private BluetoothSocket mBTSocket;
	private ReadInput mReadThread = null;

	ArrayList<Integer> final_data = new ArrayList<Integer>();

	private boolean mIsUserInitiatedDisconnect = false;

	// All controls here
	private TextView mTxtReceive;
	private EditText mEditSend;
	private Button mBtnDisconnect;
	private Button mBtnSend;
	private Button mBtnClear;
	private Button mBtnClearInput;
	private ScrollView scrollView;
	private CheckBox chkScroll;
	private CheckBox chkReceiveText;
	private TextView bpm_text;

	private XYPlot dynamicPlot;
	private XYPlot staticPlot;
	private MyPlotUpdater plotUpdater;

	private boolean mIsBluetoothConnected = false;

	private BluetoothDevice mDevice;

	private ProgressDialog progressDialog;

	private double[] fft_array;
	private double[] magnitudes;

	// FFT

	private int Fs = 128;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		ActivityHelper.initialize(this);

		fft_array = new double[400];
		magnitudes = new double[100];
		
		bpm_text = (TextView) findViewById(R.id.bpmtext);
		

		for (int i = 0; i < 200; i = i + 1) {
			final_data.add(0);
		}

		Intent intent = getIntent();
		Bundle b = intent.getExtras();
		mDevice = b.getParcelable(Homescreen.DEVICE_EXTRA);
		mDeviceUUID = UUID.fromString(b.getString(Homescreen.DEVICE_UUID));
		mMaxChars = b.getInt(Homescreen.BUFFER_SIZE);

		Log.d(TAG, "Ready");

		mBtnDisconnect = (Button) findViewById(R.id.btnDisconnect);
		mBtnSend = null;
		mBtnClear = null;
		mTxtReceive = (TextView) findViewById(R.id.txtReceive);
		mEditSend = null;
		scrollView = (ScrollView) findViewById(R.id.viewScroll);
		chkScroll = (CheckBox) findViewById(R.id.chkScroll);
		chkReceiveText = (CheckBox) findViewById(R.id.chkReceiveText);
		mBtnClearInput = (Button) findViewById(R.id.btnClearInput);

		mTxtReceive.setMovementMethod(new ScrollingMovementMethod());

		mBtnDisconnect.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				mIsUserInitiatedDisconnect = true;
				new DisConnectBT().execute();
			}
		});

		// mBtnSend.setOnClickListener(new OnClickListener() {
		//
		// @Override
		// public void onClick(View arg0) {
		// try {
		// mBTSocket.getOutputStream().write(mEditSend.getText().toString().getBytes());
		// } catch (IOException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
		// }
		// });

		// mBtnClear.setOnClickListener(new OnClickListener() {
		//
		// @Override
		// public void onClick(View arg0) {
		// mEditSend.setText("");
		// }
		// });

		mBtnClearInput.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View arg0) {
				mTxtReceive.setText("");
			}
		});

		//
		// XYPlot
		//
		// initialize our XYPlot reference:

		dynamicPlot = (XYPlot) findViewById(R.id.dynamicPlot);

		plotUpdater = new MyPlotUpdater(dynamicPlot);
		// only display whole numbers in domain labels
		dynamicPlot.getGraphWidget().setDomainValueFormat(
				new DecimalFormat("0"));

	}

	public class ReadInput implements Runnable {

		// encapsulates management of the observers watching this datasource for
		// update events:
		class MyObservable extends Observable {
			@Override
			public void notifyObservers() {
				setChanged();
				super.notifyObservers();
			}

		}

		private boolean bStop = false;
		private Thread t;

		private static final int SAMPLE_SIZE = 200;
		private MyObservable notifier;
		private int voltage = 0;

		{
			notifier = new MyObservable();
		}

		public ReadInput() {
			t = new Thread(this, "Input Thread");
			t.start();
		}

		public boolean isRunning() {
			return t.isAlive();
		}

		@Override
		public void run() {
			InputStream inputStream;

			try {
				inputStream = mBTSocket.getInputStream();
			

				int sample_counter = 0;
				while (!bStop) {
					byte[] buffer = new byte[256];
					if (inputStream.available() > 0) {

						if (sample_counter > 200 && !final_data.isEmpty()) {
							sample_counter = 0;
							
							
									runOnUiThread(new Runnable() {
								  public void run() {
									  
									//bpm_text.setText("Heart Rate = "+findBPM(final_data)+" bpm ");

								    //Toast.makeText(getApplicationContext(), "Heart Rate = "+freq+" bpm", Toast.LENGTH_SHORT).show();
								  }
								});


						} else {
							sample_counter = sample_counter + 1;
						}

						inputStream.read(buffer);
						int i = 0;
						/*
						 * This is needed because new String(buffer) is taking
						 * the entire buffer i.e. 256 chars on Android 2.3.4
						 * http://stackoverflow.com/a/8843462/1287554
						 */
						for (i = 0; i < buffer.length && buffer[i] != 0; i++) {
						}
						final String strInput = new String(buffer, 0, i);

						final String lines[] = strInput.split("[\\r\\n]+");

						/*
						 * If checked then receive text, better design would
						 * probably be to stop thread if unchecked and free
						 * resources, but this is a quick fix
						 */

						if (chkReceiveText.isChecked()) {

							if (lines.length != 0 && !lines[0].equals("")
									&& lines[0] != null) {

								try {
									voltage = Integer.parseInt(lines[0]);

									if (voltage > 150) {
										final_data.add(voltage);
										final_data.remove(0);
									}

									notifier.notifyObservers();

								}

								catch (Exception e) {
									Log.d("f", "Value crashed program is: "
											+ lines[0]);
								}
							}

							mTxtReceive.post(new Runnable() {
								@Override
								public void run() {

									mTxtReceive.append("");

									int txtLength = mTxtReceive
											.getEditableText().length();
									if (txtLength > mMaxChars) {
										mTxtReceive.getEditableText().delete(0,
												txtLength - mMaxChars);
									}

									if (chkScroll.isChecked()) { // Scroll only

										scrollView.post(new Runnable() { // Snippet
													// from
													// http://stackoverflow.com/a/4612082/1287554
													@Override
													public void run() {
														scrollView
																.fullScroll(View.FOCUS_DOWN);
													}
												});
									}
								}
							});
						}

					}
					Thread.sleep(0);
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		public int getItemCount(int series) {
			return 200;
		}

		public Number getX(int series, int index) {
			if (index >= SAMPLE_SIZE) {
				// throw new IllegalArgumentException();

			}
			return index;
		}

		public Number getY(int series, int index) {
			if (index >= SAMPLE_SIZE) {
				// throw new IllegalArgumentException();
			}
			return final_data.get(index);
		}

		public void addObserver(Observer observer) {
			notifier.addObserver(observer);
		}

		public void removeObserver(Observer observer) {
			notifier.deleteObserver(observer);
		}

		public void stop() {
			bStop = true;
		}

	}

	private class DisConnectBT extends AsyncTask<Void, Void, Void> {

		@Override
		protected void onPreExecute() {
		}

		@Override
		protected Void doInBackground(Void... params) {

			if (mReadThread != null) {
				mReadThread.stop();
				while (mReadThread.isRunning())
					; // Wait until it stops
				mReadThread = null;

			}

			try {
				mBTSocket.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			super.onPostExecute(result);
			mIsBluetoothConnected = false;
			if (mIsUserInitiatedDisconnect) {
				finish();
			}
		}

	}

	private void msg(String s) {
		Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
	}
	
	private double findBPM(ArrayList<Integer> data){
		DoubleFFT_1D fft = new DoubleFFT_1D(200);

		double[] final_data_converted = Doubles.toArray(data); 

		// put the real values into a comples array
		for (int i = 0; i < final_data_converted.length - 1; i++) {
			fft_array[2 * i] = final_data_converted[i];
			fft_array[2 * i + 1] = 0;
		}
		// perform fft
		fft.complexForward(fft_array);

		double re = 0;
		double im = 0;

		// find the magnitudes

		for (int i = 0; i < (final_data_converted.length / 2) -1; i++) {
			re = fft_array[2 * i];
			im = fft_array[2 * i + 1];
			
			magnitudes[i] = Math.sqrt(re * re + im * im);
			
			
		}

		// // find largest peak in magnitudes
		double max_magnitude = -10000;
		int max_index = -1;
		for (int i = 0; i < (final_data_converted.length / 2) - 1; i++) {
			if (magnitudes[i] > max_magnitude && i!=0) {
			
				max_magnitude = magnitudes[i];

				max_index = i;
				Log.v("rohan value", "mag = " + Arrays.toString(magnitudes) +" index = "+max_index+"");
				
			}
		}
		
		
		Log.v("rohan value", "max_index = "+max_index+"");
		//Convert index of largest peak in magnitudes to a frequency
		
		final double freq = (max_index * Fs / final_data_converted.length)*60;
	
		final int target_index = max_index;
		
		return freq;
		
	}
	

	@Override
	protected void onPause() {
		if (mBTSocket != null && mIsBluetoothConnected) {
			new DisConnectBT().execute();
		}
		Log.d(TAG, "Paused");
		super.onPause();
	}

	@Override
	protected void onResume() {
		if (mBTSocket == null || !mIsBluetoothConnected) {

			new ConnectBT().execute();

		}
		Log.d(TAG, "Resumed");
		super.onResume();
	}

	@Override
	protected void onStop() {
		Log.d(TAG, "Stopped");
		super.onStop();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		// TODO Auto-generated method stub
		super.onSaveInstanceState(outState);
	}

	private class ConnectBT extends AsyncTask<Void, Void, Void> {
		private boolean mConnectSuccessful = true;

		@Override
		protected void onPreExecute() {

			progressDialog = ProgressDialog.show(MainActivity.this, "Hold on",
					"Connecting");// http://stackoverflow.com/a/11130220/1287554
		}

		@SuppressLint("NewApi")
		@Override
		protected Void doInBackground(Void... devices) {

			try {
				if (mBTSocket == null || !mIsBluetoothConnected) {
					mBTSocket = mDevice
							.createInsecureRfcommSocketToServiceRecord(mDeviceUUID);
					BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
					mBTSocket.connect();
				}
			} catch (IOException e) {
				// Unable to connect to device
				e.printStackTrace();
				mConnectSuccessful = false;
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			super.onPostExecute(result);

			if (!mConnectSuccessful) {
				Toast.makeText(
						getApplicationContext(),
						"Could not connect to device. Is it a Serial device? Also check if the UUID is correct in the settings",
						Toast.LENGTH_LONG).show();
				finish();
			} else {

				Log.d("", "I MADE ITTTTTT");
				msg("Connected to device");
				mIsBluetoothConnected = true;

				// getInstance and position datasets:
				ReadInput data = new ReadInput();
				DynamicSeriesData sine1Series = new DynamicSeriesData(data, 0,
						"Vl-Vr");
				// SampleDynamicSeries sine2Series = new
				// SampleDynamicSeries(data, 1, "Sine 2");

				dynamicPlot.addSeries(sine1Series, new LineAndPointFormatter(
						Color.rgb(255, 255, 255), null, null));

				// create a series using a formatter with some transparency
				// applied:
				// LineAndPointFormatter f1 = new
				// LineAndPointFormatter(Color.rgb(0, 0, 200), null,
				// Color.rgb(0, 0, 80));
				// f1.getFillPaint().setAlpha(220);
				// dynamicPlot.addSeries(sine2Series, f1);

				// dynamicPlot.setGridPadding(5, 0, 5, 0);

				// hook up the plotUpdater to the data model:
				data.addObserver(plotUpdater);

				dynamicPlot.setDomainStepMode(XYStepMode.SUBDIVIDE);
				dynamicPlot.setDomainStepValue(sine1Series.size());

				// thin out domain/range tick labels so they dont overlap each
				// other:
				dynamicPlot.setTicksPerDomainLabel(5);
				dynamicPlot.setTicksPerRangeLabel(3);
				dynamicPlot.disableAllMarkup();
				dynamicPlot.getGraphWidget().setGridLinePaint(null);

				dynamicPlot.setDomainLabel("");
				dynamicPlot.setRangeLabel("");

				// freeze the range boundaries:
				dynamicPlot.setRangeBoundaries(0, 1023, BoundaryMode.FIXED);

				mReadThread = new ReadInput(); // Kick off input reader

			}

			progressDialog.dismiss();
		}

	}

	public static double[] toDoubleArray(ArrayList<Integer> list) {
		double[] intArray = new double[list.size()];
		int i = 0;
		Integer integer = 0;
		
		for (Iterator<Integer> it = list.iterator(); it.hasNext();)
			
			integer = it.next();
		
			intArray[i++] = (double) integer;

		return intArray;
	}

}
