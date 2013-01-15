package org.eweb4j.spiderman.spider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eweb4j.spiderman.infra.SpiderIOC;
import org.eweb4j.spiderman.infra.SpiderIOCs;
import org.eweb4j.spiderman.plugin.BeginPoint;
import org.eweb4j.spiderman.plugin.DigPoint;
import org.eweb4j.spiderman.plugin.DoneException;
import org.eweb4j.spiderman.plugin.DupRemovalPoint;
import org.eweb4j.spiderman.plugin.EndPoint;
import org.eweb4j.spiderman.plugin.ExtensionPoint;
import org.eweb4j.spiderman.plugin.ExtensionPoints;
import org.eweb4j.spiderman.plugin.FetchPoint;
import org.eweb4j.spiderman.plugin.ParsePoint;
import org.eweb4j.spiderman.plugin.PluginManager;
import org.eweb4j.spiderman.plugin.Point;
import org.eweb4j.spiderman.plugin.PojoPoint;
import org.eweb4j.spiderman.plugin.TargetPoint;
import org.eweb4j.spiderman.plugin.TaskPollPoint;
import org.eweb4j.spiderman.plugin.TaskPushPoint;
import org.eweb4j.spiderman.plugin.TaskSortPoint;
import org.eweb4j.spiderman.task.Task;
import org.eweb4j.spiderman.task.TaskQueue;
import org.eweb4j.spiderman.xml.Plugin;
import org.eweb4j.spiderman.xml.Plugins;
import org.eweb4j.spiderman.xml.Site;
import org.eweb4j.spiderman.xml.Target;
import org.eweb4j.util.CommonUtil;
import org.eweb4j.util.xml.BeanXMLUtil;
import org.eweb4j.util.xml.XMLReader;
import org.eweb4j.util.xml.XMLWriter;


public class Spiderman {

	public final SpiderIOC ioc = SpiderIOCs.create();
	public Boolean isStop = false;
	public Boolean isShutdownNow = false;
	private ExecutorService pool = null;
	private Collection<Site> sites = null;
	private SpiderListener listener = null;
	
	public final static Spiderman me() {
		return new Spiderman();
	}
	
	public Spiderman init() {
		return init(null);
	}
	
	public Spiderman init(SpiderListener _listener) {
		listener = _listener;
		if (listener == null)
			listener = new SpiderListenerAdaptor();
		isStop = false;
		isShutdownNow = false;
		sites = null;
		pool = null;
		try {
			loadPlugins();
			initSites();
			initPool();
		} catch (Exception e){
			listener.onInfo(Thread.currentThread(),null, "Spiderman init error.");
			listener.onError(Thread.currentThread(), null, "Spiderman init error.", e);
		}
		
		return this;
	}
	
	public Spiderman startup() {
		for (Site site : sites){
			pool.execute(new Spiderman._Executor(site));
			listener.onInfo(Thread.currentThread(), null, "spider tasks of " + site.getName() + " start... ");
		}
		
		return this;
	}
	
	public Spiderman keep(String time){
		return keep(CommonUtil.toSeconds(time).longValue()*1000);
	}
	
	public Spiderman keep(long time){
		try {
			Thread.sleep(time);
		} catch (InterruptedException e) {
		}
		shutdown();
		return this;
	}
	
	public void shutdown(){
		pool.shutdown();
		pool = null;
		isStop = true;
	}
	
	public void shutdownNow(){
		pool.shutdownNow();
		pool = null;
		isStop = true;
		isShutdownNow = true;
	}
	
	private void loadPlugins() throws Exception{
		File siteFolder = new File(Settings.website_xml_folder());
		if (!siteFolder.exists())
			throw new Exception("can not found WebSites folder -> " + siteFolder.getAbsolutePath());
		
		if (!siteFolder.isDirectory())
			throw new Exception("WebSites -> " + siteFolder.getAbsolutePath() + " must be folder !");
		
		File[] files = siteFolder.listFiles();
		if (files == null || files.length == 0){
			//generate a site.xml file
			File file = new File(siteFolder.getAbsoluteFile()+File.separator+"_site_sample_.xml");
			Site site = new Site();
			
			Plugins plugins = new Plugins();
			plugins.getPlugin().add(PluginManager.createPlugin());
			site.setPlugins(plugins);
			
			XMLWriter writer = BeanXMLUtil.getBeanXMLWriter(file, site);
			writer.setBeanName("site");
			writer.setClass("site", Site.class);
			writer.write();
		}
		
		sites = new ArrayList<Site>(files.length);
		for (File file : files){
			if (!file.exists())
				continue;
			if (!file.isFile())
				continue;
			if (!file.getName().endsWith(".xml"))
				continue;
			XMLReader reader = BeanXMLUtil.getBeanXMLReader(file);
			reader.setBeanName("site");
			reader.setClass("site", Site.class);
			Site site = reader.readOne();
			if (site == null)
				throw new Exception("site xml file error -> " + file.getAbsolutePath());
			if ("1".equals(site.getEnable())){
				sites.add(site);
			}
		}
	}
	
	private void initSites() throws Exception{
		for (Site site : sites){
			if (site.getName() == null || site.getName().trim().length() == 0)
				throw new Exception("site name required");
			if (site.getUrl() == null || site.getUrl().trim().length() == 0)
				throw new Exception("site url required");
			if (site.getTargets() == null || site.getTargets().getTarget().isEmpty())
				throw new Exception("site target required");
			
			List<Target> targets = site.getTargets().getTarget();
			if (targets == null || targets.isEmpty())
				throw new Exception("can not get any url target of site -> " + site.getName());
			
			//---------------------插件初始化开始----------------------------
			listener.onInfo(Thread.currentThread(), null, "plugins loading begin...");
			Collection<Plugin> plugins = site.getPlugins().getPlugin();
			//加载网站插件配置
			try {
				PluginManager pluginMgr = new PluginManager();
				pluginMgr.loadPluginConf(plugins, listener);
				
				//加载TaskPoll扩展点实现类
				ExtensionPoint<TaskPollPoint> taskPollPoint = pluginMgr.getExtensionPoint(ExtensionPoints.task_poll);
				if (taskPollPoint != null) {
					site.taskPollPointImpls = taskPollPoint.getExtensions();
					firstInitPoint(site.taskPollPointImpls, site, listener);
				}
				
				//加载Begin扩展点实现类
				ExtensionPoint<BeginPoint> beginPoint = pluginMgr.getExtensionPoint(ExtensionPoints.begin);
				if (beginPoint != null){
					site.beginPointImpls = beginPoint.getExtensions();
					firstInitPoint(site.beginPointImpls, site, listener);
				}
				
				//加载Fetch扩展点实现类
				ExtensionPoint<FetchPoint> fetchPoint = pluginMgr.getExtensionPoint(ExtensionPoints.fetch);
				if (fetchPoint != null){
					site.fetchPointImpls = fetchPoint.getExtensions();
					firstInitPoint(site.fetchPointImpls, site, listener);
				}
				
				//加载Dig扩展点实现类
				ExtensionPoint<DigPoint> digPoint = pluginMgr.getExtensionPoint(ExtensionPoints.dig);
				if (digPoint != null){
					site.digPointImpls = digPoint.getExtensions();
					firstInitPoint(site.digPointImpls, site, listener);
				}
				
				//加载DupRemoval扩展点实现类
				ExtensionPoint<DupRemovalPoint> dupRemovalPoint = pluginMgr.getExtensionPoint(ExtensionPoints.dup_removal);
				if (dupRemovalPoint != null){
					site.dupRemovalPointImpls = dupRemovalPoint.getExtensions();
					firstInitPoint(site.dupRemovalPointImpls, site, listener);
				}
				//加载TaskSort扩展点实现类
				ExtensionPoint<TaskSortPoint> taskSortPoint = pluginMgr.getExtensionPoint(ExtensionPoints.task_sort);
				if (taskSortPoint != null){
					site.taskSortPointImpls = taskSortPoint.getExtensions();
					firstInitPoint(site.taskSortPointImpls, site, listener);
				}
				
				//加载TaskPush扩展点实现类
				ExtensionPoint<TaskPushPoint> taskPushPoint = pluginMgr.getExtensionPoint(ExtensionPoints.task_push);
				if (taskPushPoint != null){
					site.taskPushPointImpls = taskPushPoint.getExtensions();
					firstInitPoint(site.taskPushPointImpls, site, listener);
				}
				
				//加载Target扩展点实现类
				ExtensionPoint<TargetPoint> targetPoint = pluginMgr.getExtensionPoint(ExtensionPoints.target);
				if (targetPoint != null){
					site.targetPointImpls = targetPoint.getExtensions();
					firstInitPoint(site.targetPointImpls, site, listener);
				}
				
				//加载Parse扩展点实现类
				ExtensionPoint<ParsePoint> parsePoint = pluginMgr.getExtensionPoint(ExtensionPoints.parse);
				if (parsePoint != null){
					site.parsePointImpls = parsePoint.getExtensions();
					firstInitPoint(site.parsePointImpls, site, listener);
				}
				
				//加载Pojo扩展点实现类
				ExtensionPoint<PojoPoint> pojoPoint = pluginMgr.getExtensionPoint(ExtensionPoints.pojo);
				if (pojoPoint != null){
					site.pojoPointImpls = pojoPoint.getExtensions();
					firstInitPoint(site.pojoPointImpls, site, listener);
				}
				
				//加载End扩展点实现类
				ExtensionPoint<EndPoint> endPoint = pluginMgr.getExtensionPoint(ExtensionPoints.end);
				if (endPoint != null){
					site.endPointImpls = endPoint.getExtensions();
					firstInitPoint(site.endPointImpls, site, listener);
				}
			//---------------------------插件初始化完毕----------------------------------
			} catch(Exception e){
				throw new Exception("Site["+site.getName()+"] loading plugins fail", e);
			}
			
			//初始化网站的队列容器
			site.queue = new TaskQueue();
			//初始化网站目标Model计数器
			site.counter = new Counter();
		}
	}
	
	private void firstInitPoint(Collection<? extends Point> points, Site site, SpiderListener listener){
		for (Point point : points){
			point.init(site, listener);
		}
	}
	
	private void destroyPoint(Collection<? extends Point> points){
		if (points == null)
			return ;
		for (Point point : points){
			try {
				point.destroy();
			} catch (DoneException e){
				continue;
			}
			point = null;
		}
	}
	
	private void destroyPoint(Site site) {
		destroyPoint(site.beginPointImpls);
		destroyPoint(site.digPointImpls);
		destroyPoint(site.dupRemovalPointImpls);
		destroyPoint(site.endPointImpls);
		destroyPoint(site.fetchPointImpls);
		destroyPoint(site.parsePointImpls);
		destroyPoint(site.pojoPointImpls);
		destroyPoint(site.targetPointImpls);
		destroyPoint(site.taskPollPointImpls);
		destroyPoint(site.taskPushPointImpls);
		destroyPoint(site.taskSortPointImpls);
	}
	
	private void initPool(){
		if (pool == null){
			int size = sites.size();
			pool = new ThreadPoolExecutor(size, size,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>());
			
			listener.onInfo(Thread.currentThread(), null, "init thread pool size->"+size+" success ");
		}
	}
	
	private class _Executor implements Runnable{
		private Site site = null;
		private ExecutorService _pool = null;
		
		public _Executor(Site site){
			this.site = site;
			String strSize = site.getThread();
			int size = Integer.parseInt(strSize);
			listener.onInfo(Thread.currentThread(), null, "site thread size -> " + size);
			RejectedExecutionHandler rejectedHandler = new RejectedExecutionHandler() {
				public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
					//拿到被弹出来的爬虫引用
					Spider spider = (Spider)r;
					try {
						//将该爬虫的任务 task 放回队列
						spider.pushTask(Arrays.asList(spider.task));
						String info = "repush the task->"+spider.task+" to the Queue.";
						spider.listener.onError(Thread.currentThread(), spider.task, info, new Exception(info));
					} catch (Exception e) {
						String err = "could not repush the task to the Queue. cause -> " + e.toString();
						spider.listener.onError(Thread.currentThread(), spider.task, err, e);
					}
				}
			};
			
			if (size > 0)
				this._pool = new ThreadPoolExecutor(size, size,
						60L, TimeUnit.SECONDS,
	                    new LinkedBlockingQueue<Runnable>(),
	                    rejectedHandler);
			else
				this._pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
	                    60L, TimeUnit.SECONDS,
	                    new SynchronousQueue<Runnable>(),
	                    rejectedHandler);
		}
		
		public void run() {
			// 运行种子任务
			Task feedTask = new Task(new String(this.site.getUrl()), this.site, 10);
			Spider feedSpider = new Spider();
			feedSpider.init(feedTask, listener);
			feedSpider.run();
			
			final float times = CommonUtil.toSeconds(this.site.getSchedule()) * 1000;
			long start = System.currentTimeMillis();
			while(true){
				try {
					if (isStop) {
						if (isShutdownNow) _pool.shutdownNow(); else _pool.shutdown();
						_pool = null;
						listener.onInfo(Thread.currentThread(), null, site.getName() + ".Spider shutdown...");
						destroyPoint(this.site);
						return ;
					}
					
					//扩展点：TaskPoll
					Task task = null;
					Collection<TaskPollPoint> taskPollPoints = site.taskPollPointImpls;
					if (taskPollPoints != null && !taskPollPoints.isEmpty()){
						for (TaskPollPoint point : taskPollPoints){
							task = point.pollTask();
						}
					}
					
					if (task == null){
						long wait = CommonUtil.toSeconds(site.getWaitQueue()).longValue();
						listener.onInfo(Thread.currentThread(), null, "queue empty wait for -> " + wait + " seconds");
						if (wait > 0) {
							try {
								Thread.sleep(wait * 1000);
							} catch (Exception e){
								
							}
						}
						continue;
					}
					
					Spider spider = new Spider();
					spider.init(task, listener);
					_pool.execute(spider);
					
				}catch (DoneException e) {
					listener.onInfo(Thread.currentThread(), null, e.toString());
					return ;
				} catch (Exception e) {
					listener.onError(Thread.currentThread(), null, e.toString(), e);
				}finally{
					long cost = System.currentTimeMillis() - start;
					if (cost >= times){ 
						// 运行种子任务
						feedSpider.run();
						listener.onInfo(Thread.currentThread(), null, " shcedule FeedSpider per "+times+", now cost time ->"+cost);
						start = System.currentTimeMillis();//重新计时
					}
				}
			}
		}
	} 
	
}
