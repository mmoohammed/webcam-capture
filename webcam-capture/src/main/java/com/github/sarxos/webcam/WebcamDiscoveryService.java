package com.github.sarxos.webcam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WebcamDiscoveryService implements Runnable {

	private static final Logger LOG = LoggerFactory.getLogger(WebcamDiscoveryService.class);

	private static final class WebcamsDiscovery implements Callable<List<Webcam>>, ThreadFactory {

		private final WebcamDriver driver;

		public WebcamsDiscovery(WebcamDriver driver) {
			this.driver = driver;
		}

		@Override
		public List<Webcam> call() throws Exception {
			return toWebcams(driver.getDevices());
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "webcam-discovery-service");
			t.setDaemon(true);
			return t;
		}
	}

	private final WebcamDriver driver;
	private final WebcamDiscoverySupport support;

	private volatile List<Webcam> webcams = null;

	private volatile boolean running = false;

	private Thread runner = null;

	protected WebcamDiscoveryService(WebcamDriver driver) {
		this.driver = driver;
		this.support = (WebcamDiscoverySupport) (driver instanceof WebcamDiscoverySupport ? driver : null);
	}

	private static List<Webcam> toWebcams(List<WebcamDevice> devices) {
		List<Webcam> webcams = new ArrayList<Webcam>();
		for (WebcamDevice device : devices) {
			webcams.add(new Webcam(device));
		}
		return webcams;
	}

	/**
	 * Get list of devices used by webcams.
	 * 
	 * @return List of webcam devices
	 */
	private static List<WebcamDevice> getDevices(List<Webcam> webcams) {
		List<WebcamDevice> devices = new ArrayList<WebcamDevice>();
		for (Webcam webcam : webcams) {
			devices.add(webcam.getDevice());
		}
		return devices;
	}

	public synchronized List<Webcam> getWebcams(long timeout, TimeUnit tunit) throws TimeoutException {

		if (timeout < 0) {
			throw new IllegalArgumentException("Timeout cannot be negative");
		}
		if (tunit == null) {
			throw new IllegalArgumentException("Time unit cannot be null!");
		}

		if (webcams == null) {

			WebcamsDiscovery discovery = new WebcamsDiscovery(driver);
			ExecutorService executor = Executors.newSingleThreadExecutor(discovery);
			Future<List<Webcam>> future = executor.submit(discovery);

			executor.shutdown();

			try {

				executor.awaitTermination(timeout, tunit);

				if (future.isDone()) {
					webcams = future.get();
				} else {
					future.cancel(true);
				}

			} catch (InterruptedException e) {
				throw new WebcamException(e);
			} catch (ExecutionException e) {
				throw new WebcamException(e);
			}

			if (webcams == null) {
				throw new TimeoutException(String.format("Webcams discovery timeout (%d ms) has been exceeded", timeout));
			}

			WebcamDiscoveryListener[] listeners = Webcam.getDiscoveryListeners();
			for (Webcam webcam : webcams) {
				notifyWebcamFound(webcam, listeners);
			}

			if (Webcam.isHandleTermSignal()) {
				WebcamDeallocator.store(webcams.toArray(new Webcam[webcams.size()]));
			}
		}

		return Collections.unmodifiableList(webcams);
	}

	@Override
	public void run() {

		// do not run if driver does not support discovery

		if (support == null) {
			return;
		}

		running = true;

		// wait initial time interval since devices has been initially
		// discovered

		do {

			delay();

			WebcamDiscoveryListener[] listeners = Webcam.getDiscoveryListeners();

			// do nothing when there are no listeners to be notified

			if (listeners.length == 0) {
				continue;
			}

			List<WebcamDevice> tmpnew = driver.getDevices();
			List<WebcamDevice> tmpold = null;

			try {
				tmpold = getDevices(getWebcams(Long.MAX_VALUE, TimeUnit.MILLISECONDS));
			} catch (TimeoutException e) {
				throw new WebcamException(e);
			}

			// convert to linked list due to O(1) on remove operation on
			// iterator versus O(n) for the same operation in array list

			List<WebcamDevice> oldones = new LinkedList<WebcamDevice>(tmpold);
			List<WebcamDevice> newones = new LinkedList<WebcamDevice>(tmpnew);

			Iterator<WebcamDevice> oi = oldones.iterator();
			Iterator<WebcamDevice> ni = null;

			WebcamDevice od = null; // old device
			WebcamDevice nd = null; // new device

			// reduce lists

			while (oi.hasNext()) {

				od = oi.next();
				ni = newones.iterator();

				while (ni.hasNext()) {

					nd = ni.next();

					// remove both elements, if device name is the same, which
					// actually means that device is exactly the same

					if (nd.getName().equals(od.getName())) {
						ni.remove();
						oi.remove();
						break;
					}
				}
			}

			// if any left in old ones it means that devices has been removed
			if (oldones.size() > 0) {

				List<Webcam> notified = new ArrayList<Webcam>();

				for (WebcamDevice device : oldones) {
					for (Webcam webcam : webcams) {
						if (webcam.getDevice().getName().equals(device.getName())) {
							notified.add(webcam);
							break;
						}
					}
				}

				setCurrentWebcams(tmpnew);

				for (Webcam webcam : notified) {
					notifyWebcamGone(webcam, listeners);
					webcam.dispose();
				}
			}

			// if any left in new ones it means that devices has been added
			if (newones.size() > 0) {

				setCurrentWebcams(tmpnew);

				for (WebcamDevice device : newones) {
					for (Webcam webcam : webcams) {
						if (webcam.getDevice().getName().equals(device.getName())) {
							notifyWebcamFound(webcam, listeners);
							break;
						}
					}
				}
			}

		} while (running);
	}

	private void setCurrentWebcams(List<WebcamDevice> devices) {
		webcams = toWebcams(devices);
		if (Webcam.isHandleTermSignal()) {
			WebcamDeallocator.unstore();
			WebcamDeallocator.store(webcams.toArray(new Webcam[webcams.size()]));
		}
	}

	private static void notifyWebcamGone(Webcam webcam, WebcamDiscoveryListener[] listeners) {
		WebcamDiscoveryEvent event = new WebcamDiscoveryEvent(webcam, WebcamDiscoveryEvent.REMOVED);
		for (WebcamDiscoveryListener l : listeners) {
			try {
				l.webcamGone(event);
			} catch (Exception e) {
				LOG.error(String.format("Webcam gone, exception when calling listener %s", l.getClass()), e);
			}
		}
	}

	private static void notifyWebcamFound(Webcam webcam, WebcamDiscoveryListener[] listeners) {
		WebcamDiscoveryEvent event = new WebcamDiscoveryEvent(webcam, WebcamDiscoveryEvent.ADDED);
		for (WebcamDiscoveryListener l : listeners) {
			try {
				l.webcamFound(event);
			} catch (Exception e) {
				LOG.error(String.format("Webcam found, exception when calling listener %s", l.getClass()), e);
			}
		}
	}

	private void delay() {
		try {
			Thread.sleep(support.getScanInterval());
		} catch (InterruptedException e) {
			throw new WebcamException(e);
		}
	}

	/**
	 * Stop discovery service.
	 */
	public synchronized void stop() {

		running = false;

		if (runner != null) {
			return;
		}

		try {
			runner.join();
		} catch (InterruptedException e) {
			throw new WebcamException("Joint interrupted");
		}

		runner = null;
	}

	/**
	 * Start discovery service.
	 */
	public synchronized void start() {

		if (runner != null) {
			return;
		}

		runner = new Thread(this, "webcam-discovery-service");
		runner.setDaemon(true);
		runner.start();
	}

	/**
	 * Is discovery service running?
	 * 
	 * @return True or false
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Cleanup.
	 */
	protected synchronized void shutdown() {

		stop();

		for (Webcam webcam : webcams) {
			webcam.dispose();
		}

		webcams.clear();

		if (Webcam.isHandleTermSignal()) {
			WebcamDeallocator.unstore();
		}
	}
}
