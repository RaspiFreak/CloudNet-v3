package de.dytanic.cloudnet.service;

import de.dytanic.cloudnet.CloudNet;
import de.dytanic.cloudnet.cluster.IClusterNodeServer;
import de.dytanic.cloudnet.common.Validate;
import de.dytanic.cloudnet.common.collection.Iterables;
import de.dytanic.cloudnet.common.collection.Maps;
import de.dytanic.cloudnet.driver.CloudNetDriver;
import de.dytanic.cloudnet.driver.network.def.packet.PacketClientServerServiceInfoPublisher;
import de.dytanic.cloudnet.driver.service.*;
import de.dytanic.cloudnet.event.service.CloudServiceCreateEvent;
import de.dytanic.cloudnet.event.service.task.ServiceTaskAddEvent;
import de.dytanic.cloudnet.event.service.task.ServiceTaskRemoveEvent;
import de.dytanic.cloudnet.util.PortValidator;
import lombok.Getter;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

@Getter
public final class DefaultCloudServiceManager implements ICloudServiceManager {

    protected static final ICloudServiceFactory DEFAULT_FACTORY = new JVMCloudServiceFactory();

    protected final File
        tempDirectory = new File(System.getProperty("cloudnet.tempDir.services", "temp/services")),
        persistenceServicesDirectory = new File(System.getProperty("cloudnet.persistable.services.path", "local/services"));

    protected final Map<UUID, ServiceInfoSnapshot> globalServiceInfoSnapshots = Maps.newConcurrentHashMap();

    protected final Map<UUID, ICloudService> cloudServices = Maps.newConcurrentHashMap();

    protected final Map<String, ICloudServiceFactory> cloudServiceFactories = Maps.newConcurrentHashMap();

    protected final DefaultCloudServiceManagerConfiguration config = new DefaultCloudServiceManagerConfiguration();

    @Override
    public List<ServiceTask> getServiceTasks()
    {
        return config.getTasks();
    }

    @Override
    public void addPermanentServiceTask(ServiceTask task)
    {
        Validate.checkNotNull(task);

        if (isTaskPresent(task.getName()))
            removePermanentServiceTask(task);

        ServiceTaskAddEvent serviceTaskAddEvent = new ServiceTaskAddEvent(this, task);
        CloudNetDriver.getInstance().getEventManager().callEvent(serviceTaskAddEvent);

        if (!serviceTaskAddEvent.isCancelled())
            this.config.getTasks().add(task);

        this.config.save();
        CloudNet.getInstance().updateServiceTasksInCluster();
    }

    @Override
    public void removePermanentServiceTask(ServiceTask task)
    {
        Validate.checkNotNull(task);
        this.removePermanentServiceTask(task.getName());
    }

    @Override
    public void removePermanentServiceTask(String name)
    {
        Validate.checkNotNull(name);

        for (ServiceTask serviceTask : this.config.getTasks())
            if (serviceTask.getName().equalsIgnoreCase(name))
            {
                CloudNetDriver.getInstance().getEventManager().callEvent(new ServiceTaskRemoveEvent(this, serviceTask));
                this.config.getTasks().remove(serviceTask);
            }

        this.config.save();
        CloudNet.getInstance().updateServiceTasksInCluster();
    }

    @Override
    public void removeAllPermanentServiceTasks()
    {
        this.config.getTasks().clear();
        this.config.save();
    }

    @Override
    public ServiceTask getServiceTask(String name)
    {
        Validate.checkNotNull(name);

        return Iterables.first(this.getServiceTasks(), new Predicate<ServiceTask>() {
            @Override
            public boolean test(ServiceTask serviceTask)
            {
                return serviceTask.getName().equalsIgnoreCase(name);
            }
        });
    }

    @Override
    public boolean isTaskPresent(String name)
    {
        Validate.checkNotNull(name);
        return this.getServiceTask(name) != null;
    }

    @Override
    public void setServiceTasks(Collection<ServiceTask> tasks)
    {
        Validate.checkNotNull(tasks);

        this.config.getTasks().clear();
        this.config.getTasks().addAll(tasks);
        this.config.save();
    }

    @Override
    public List<GroupConfiguration> getGroupConfigurations()
    {
        return this.config.getGroups();
    }

    @Override
    public GroupConfiguration getGroupConfiguration(String name)
    {
        Validate.checkNotNull(name);

        return Iterables.first(this.getGroupConfigurations(), new Predicate<GroupConfiguration>() {
            @Override
            public boolean test(GroupConfiguration groupConfiguration)
            {
                return groupConfiguration.getName().equalsIgnoreCase(name);
            }
        });
    }

    @Override
    public void addGroupConfiguration(GroupConfiguration groupConfiguration)
    {
        Validate.checkNotNull(groupConfiguration);

        for (GroupConfiguration group : this.config.getGroups())
            if (group.getName().equalsIgnoreCase(groupConfiguration.getName()))
                this.config.getGroups().remove(groupConfiguration);

        this.config.getGroups().add(groupConfiguration);
        this.config.save();
        CloudNet.getInstance().updateGroupConfigurationsInCluster();
    }

    @Override
    public void removeGroupConfiguration(GroupConfiguration groupConfiguration)
    {
        Validate.checkNotNull(groupConfiguration);

        this.removeGroupConfiguration(groupConfiguration.getName());
    }

    @Override
    public void removeGroupConfiguration(String name)
    {
        Validate.checkNotNull(name);

        for (GroupConfiguration group : this.config.getGroups())
            if (group.getName().equalsIgnoreCase(name))
                this.config.getGroups().remove(group);

        this.config.save();
        CloudNet.getInstance().updateGroupConfigurationsInCluster();
    }

    @Override
    public boolean isGroupConfigurationPresent(String group)
    {
        Validate.checkNotNull(group);

        return Iterables.first(this.getGroupConfigurations(), new Predicate<GroupConfiguration>() {
            @Override
            public boolean test(GroupConfiguration groupConfiguration)
            {
                return groupConfiguration.getName().equalsIgnoreCase(group);
            }
        }) != null;
    }

    @Override
    public void setGroupConfigurations(Collection<GroupConfiguration> groupConfigurations)
    {
        Validate.checkNotNull(groupConfigurations);

        this.config.getGroups().clear();
        this.config.getGroups().addAll(groupConfigurations);
        this.config.save();
    }

    @Override
    public ICloudService runTask(ServiceTask serviceTask)
    {
        Validate.checkNotNull(serviceTask);

        return this.runTask(
            serviceTask.getName(),
            serviceTask.getRuntime(),
            serviceTask.isAutoDeleteOnStop(),
            serviceTask.isStaticServices(),
            Iterables.newArrayList(serviceTask.getIncludes()),
            Iterables.newArrayList(serviceTask.getTemplates()),
            Iterables.newArrayList(serviceTask.getDeployments()),
            Iterables.newArrayList(serviceTask.getGroups()),
            new ProcessConfiguration(
                serviceTask.getProcessConfiguration().getEnvironment(),
                serviceTask.getProcessConfiguration().getMaxHeapMemorySize(),
                serviceTask.getProcessConfiguration().getJvmOptions()
            ),
            serviceTask.getStartPort()
        );
    }

    @Override
    public ICloudService runTask(ServiceConfiguration serviceConfiguration)
    {
        CloudServiceCreateEvent cloudServiceCreateEvent = new CloudServiceCreateEvent(serviceConfiguration);
        CloudNetDriver.getInstance().getEventManager().callEvent(cloudServiceCreateEvent);

        if (cloudServiceCreateEvent.isCancelled())
            return null;

        serviceConfiguration.setPort(checkAndReplacePort(serviceConfiguration.getPort()));

        ICloudService cloudService = null;

        if (serviceConfiguration.getRuntime() != null && this.cloudServiceFactories.containsKey(serviceConfiguration.getRuntime()))
            cloudService = this.cloudServiceFactories.get(serviceConfiguration.getRuntime()).createCloudService(this, serviceConfiguration);

        if (cloudService == null)
            cloudService = DEFAULT_FACTORY.createCloudService(this, serviceConfiguration);

        if (cloudService != null)
        {
            this.cloudServices.put(cloudService.getServiceId().getUniqueId(), cloudService);
            this.globalServiceInfoSnapshots.put(cloudService.getServiceId().getUniqueId(), cloudService.getServiceInfoSnapshot());

            CloudNet.getInstance().getNetworkClient()
                .sendPacket(new PacketClientServerServiceInfoPublisher(cloudService.getServiceInfoSnapshot(), PacketClientServerServiceInfoPublisher.PublisherType.REGISTER));
            CloudNet.getInstance().getNetworkServer()
                .sendPacket(new PacketClientServerServiceInfoPublisher(cloudService.getServiceInfoSnapshot(), PacketClientServerServiceInfoPublisher.PublisherType.REGISTER));

            CloudNet.getInstance().publishNetworkClusterNodeInfoSnapshotUpdate();
        }

        return cloudService;
    }

    @Override
    public ICloudService runTask(
        String name,
        String runtime,
        boolean autoDeleteOnStop,
        boolean staticService,
        Collection<ServiceRemoteInclusion> includes,
        Collection<ServiceTemplate> templates,
        Collection<ServiceDeployment> deployments,
        Collection<String> groups,
        ProcessConfiguration processConfiguration,
        Integer port
    )
    {

        Validate.checkNotNull(includes);
        Validate.checkNotNull(templates);
        Validate.checkNotNull(deployments);
        Validate.checkNotNull(groups);
        Validate.checkNotNull(processConfiguration);

        int taskId = 1;

        Collection<Integer> taskIdList = Iterables.newArrayList();

        for (IClusterNodeServer clusterNodeServer : CloudNet.getInstance().getClusterNodeServerProvider().getNodeServers())
        {
            Collection<Integer> ids = clusterNodeServer.getReservedTaskIds(name);

            if (ids != null) taskIdList.addAll(ids);
        }

        taskIdList.addAll(getReservedTaskIds(name));

        while (taskIdList.contains(taskId)) taskId++;

        for (GroupConfiguration groupConfiguration : this.getGroupConfigurations())
            if (groups.contains(groupConfiguration.getName()))
            {
                includes.addAll(groupConfiguration.getIncludes());
                templates.addAll(groupConfiguration.getTemplates());
                deployments.addAll(groupConfiguration.getDeployments());
            }

        ServiceConfiguration serviceConfiguration = new ServiceConfiguration(
            new ServiceId(
                UUID.randomUUID(),
                CloudNet.getInstance().getConfig().getIdentity().getUniqueId(),
                name,
                taskId,
                processConfiguration.getEnvironment()
            ),
            runtime,
            autoDeleteOnStop,
            staticService,
            groups.toArray(new String[0]),
            includes.toArray(new ServiceRemoteInclusion[0]),
            templates.toArray(new ServiceTemplate[0]),
            deployments.toArray(new ServiceDeployment[0]),
            processConfiguration,
            port
        );

        return this.runTask(serviceConfiguration);
    }

    @Override
    public void startAllCloudServices()
    {
        for (ICloudService cloudService : this.cloudServices.values())
            try
            {
                cloudService.start();
            } catch (Exception e)
            {
                e.printStackTrace();
            }
    }

    @Override
    public void stopAllCloudServices()
    {
        for (ICloudService cloudService : this.cloudServices.values())
            try
            {
                cloudService.stop();
            } catch (Exception e)
            {
                e.printStackTrace();
            }
    }

    @Override
    public void deleteAllCloudServices()
    {
        for (ICloudService cloudService : this.cloudServices.values())
            try
            {
                cloudService.delete();
            } catch (Exception e)
            {
                e.printStackTrace();
            }
    }

    @Override
    public ICloudService getCloudService(UUID uniqueId)
    {
        Validate.checkNotNull(uniqueId);

        return this.cloudServices.get(uniqueId);
    }

    @Override
    public ICloudService getCloudService(Predicate<ICloudService> predicate)
    {
        Validate.checkNotNull(predicate);

        return Iterables.first(this.cloudServices.values(), predicate);
    }

    @Override
    public Collection<ICloudService> getCloudServices(String taskName)
    {
        Validate.checkNotNull(taskName);

        return Iterables.filter(this.cloudServices.values(), new Predicate<ICloudService>() {
            @Override
            public boolean test(ICloudService iCloudService)
            {
                return iCloudService.getServiceId().getTaskName().equalsIgnoreCase(taskName);
            }
        });
    }

    @Override
    public Collection<ICloudService> getCloudServices(Predicate<ICloudService> predicate)
    {
        Validate.checkNotNull(predicate);

        return Iterables.filter(this.cloudServices.values(), predicate);
    }

    @Override
    public Collection<ICloudService> getServices()
    {
        return Collections.unmodifiableCollection(this.cloudServices.values());
    }

    @Override
    public ServiceInfoSnapshot getServiceInfoSnapshot(UUID uniqueId)
    {
        Validate.checkNotNull(uniqueId);

        return this.globalServiceInfoSnapshots.get(uniqueId);
    }

    @Override
    public ServiceInfoSnapshot getServiceInfoSnapshot(Predicate<ServiceInfoSnapshot> predicate)
    {
        Validate.checkNotNull(predicate);

        return Iterables.first(this.globalServiceInfoSnapshots.values(), predicate);
    }

    @Override
    public Collection<ServiceInfoSnapshot> getServiceInfoSnapshots(String taskName)
    {
        Validate.checkNotNull(taskName);

        return this.getServiceInfoSnapshots(new Predicate<ServiceInfoSnapshot>() {
            @Override
            public boolean test(ServiceInfoSnapshot serviceInfoSnapshot)
            {
                return serviceInfoSnapshot.getServiceId().getTaskName().equalsIgnoreCase(taskName);
            }
        });
    }

    @Override
    public Collection<ServiceInfoSnapshot> getServiceInfoSnapshots(ServiceEnvironmentType environment)
    {
        Validate.checkNotNull(environment);

        return this.getServiceInfoSnapshots(new Predicate<ServiceInfoSnapshot>() {
            @Override
            public boolean test(ServiceInfoSnapshot serviceInfoSnapshot)
            {
                return serviceInfoSnapshot.getServiceId().getEnvironment() == environment;
            }
        });
    }

    @Override
    public Collection<ServiceInfoSnapshot> getServiceInfoSnapshots(Predicate<ServiceInfoSnapshot> predicate)
    {
        Validate.checkNotNull(predicate);

        return Iterables.filter(this.globalServiceInfoSnapshots.values(), predicate);
    }

    @Override
    public Collection<ServiceInfoSnapshot> getServiceInfoSnapshots()
    {
        return this.globalServiceInfoSnapshots.values();
    }

    @Override
    public Collection<Integer> getReservedTaskIds(String task)
    {
        Validate.checkNotNull(task);

        Collection<Integer> taskIdList = Iterables.newArrayList();

        for (ServiceInfoSnapshot serviceInfoSnapshot : this.globalServiceInfoSnapshots.values())
            if (serviceInfoSnapshot.getServiceId().getTaskName().equalsIgnoreCase(task))
                taskIdList.add(serviceInfoSnapshot.getServiceId().getTaskServiceId());

        return taskIdList;
    }

    @Override
    public void reload()
    {
        this.config.load();
        CloudNet.getInstance().updateGroupConfigurationsInCluster();
        CloudNet.getInstance().updateServiceTasksInCluster();
    }

    @Override
    public int getCurrentUsedHeapMemory()
    {
        int value = 0;

        for (ICloudService cloudService : this.cloudServices.values())
            if (cloudService.getLifeCycle() == ServiceLifeCycle.RUNNING)
                value += cloudService.getConfiguredMaxHeapMemory();

        return value;
    }

    @Override
    public int getCurrentReservedMemory()
    {
        int value = 0;

        for (ICloudService cloudService : this.cloudServices.values())
            value += cloudService.getConfiguredMaxHeapMemory();

        return value;
    }

    /*= ------------------------------------------------------- =*/

    private int checkAndReplacePort(int port)
    {
        Collection<Integer> ports = Iterables.map(this.cloudServices.values(), new Function<ICloudService, Integer>() {
            @Override
            public Integer apply(ICloudService iCloudService)
            {
                return iCloudService.getServiceConfiguration().getPort();
            }
        });

        while (ports.contains(port))
            port++;

        while (!PortValidator.checkPort(port))
            port++;

        return port;
    }
}