/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.service.notification;

import static net.java.sip.communicator.service.notification.NotificationAction.ACTION_COMMAND;
import static net.java.sip.communicator.service.notification.NotificationAction.ACTION_LOG_MESSAGE;
import static net.java.sip.communicator.service.notification.NotificationAction.ACTION_POPUP_MESSAGE;
import static net.java.sip.communicator.service.notification.NotificationAction.ACTION_SOUND;
import static net.java.sip.communicator.service.notification.NotificationAction.NUM_ACTIONS;
import static net.java.sip.communicator.service.notification.event.NotificationActionTypeEvent.ACTION_ADDED;
import static net.java.sip.communicator.service.notification.event.NotificationActionTypeEvent.ACTION_CHANGED;
import static net.java.sip.communicator.service.notification.event.NotificationActionTypeEvent.ACTION_REMOVED;
import static net.java.sip.communicator.service.notification.event.NotificationEventTypeEvent.EVENT_TYPE_ADDED;
import static net.java.sip.communicator.service.notification.event.NotificationEventTypeEvent.EVENT_TYPE_REMOVED;

import java.util.*;

import net.java.sip.communicator.service.notification.event.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;

/**
 * The implementation of the <tt>NotificationService</tt>.
 * 
 * @author Yana Stamcheva
 * @author Ingo Bauersachs
 */
class NotificationServiceImpl
    implements NotificationService
{
    private final Logger logger
        = Logger.getLogger(NotificationServiceImpl.class);

    private final ConfigurationService configService =
        NotificationServiceActivator.getConfigurationService();

    private static final String NOTIFICATIONS_PREFIX
        = "net.java.sip.communicator.impl.notifications";

    /**
     * A set of all registered event notifications.
     */
    private final Map<String, Notification> notifications
        = new HashMap<String, Notification>();

    /**
     * A set of all registered event notifications.
     */
    private final Map<String, Notification> defaultNotifications
        = new HashMap<String, Notification>();

    /**
     * Contains the notification handler per action type.
     */
    private final Map<String, NotificationHandler> handlers
        = new HashMap<String, NotificationHandler>();

    /**
     * A list of all registered <tt>NotificationChangeListener</tt>s.
     */
    private final List<NotificationChangeListener> changeListeners
        = new Vector<NotificationChangeListener>();

    /**
     * Queue to cache fired notifications before all handlers are registered.
     */
    private Queue<NotificationData> notificationCache
        = new LinkedList<NotificationData>();

    /**
     * Creates an instance of <tt>NotificationServiceImpl</tt> by loading all
     * previously saved notifications. 
     */
    NotificationServiceImpl()
    {
        // Load all previously saved notifications.
        this.loadNotifications();
    }

    /**
     * Creates a new <tt>EventNotification</tt> or obtains the corresponding
     * existing one and registers a new action in it.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registering it) that we are setting an action for.
     * @param action the <tt>NotificationAction</tt> responsible for
     * handling the given <tt>actionType</tt> 
     */
    public void registerNotificationForEvent(   String eventType,
                                                NotificationAction action)
    {
        Notification notification = null;

        if(notifications.containsKey(eventType))
            notification = notifications.get(eventType);
        else
        {
            notification = new Notification(eventType);
            notifications.put(eventType, notification);

            this.fireNotificationEventTypeEvent(
                EVENT_TYPE_ADDED, eventType);
        }

        Object existingAction = notification.addAction(action);

        // We fire the appropriate event depending on whether this is an
        // already existing actionType or a new one.
        if (existingAction != null)
        {
            fireNotificationActionTypeEvent(
                ACTION_CHANGED,
                eventType,
                action);
        }
        else
        {
            fireNotificationActionTypeEvent(
                ACTION_ADDED,
                eventType,
                action);
        }

        // Save the notification through the ConfigurationService.
        this.saveNotification(  eventType,
                                action,
                                true,
                                false);
    }

    /**
     * Creates a new <tt>EventNotification</tt> or obtains the corresponding
     * existing one and registers a new action in it.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registering it) that we are setting an action for.
     * @param actionType the type of the action that is to be executed when the
     * specified event occurs (could be one of the ACTION_XXX fields).
     * @param actionDescriptor a String containing a description of the action
     * (a URI to the sound file for audio notifications or a command line for
     * exec action types) that should be executed when the action occurs.
     * @param defaultMessage the default message to use if no specific message
     * has been provided when firing the notification.
     */
    public void registerNotificationForEvent(   String eventType,
                                                String actionType,
                                                String actionDescriptor,
                                                String defaultMessage)
    {
        if (logger.isDebugEnabled())
            logger.debug("Registering event " + eventType + "/" + 
            actionType + "/" + actionDescriptor + "/" + defaultMessage);

        if (actionType.equals(ACTION_SOUND))
        {
            Notification notification = defaultNotifications.get(eventType);
            SoundNotificationAction action =
                (SoundNotificationAction) notification.getAction(ACTION_SOUND);
            registerNotificationForEvent (
                    eventType,
                    new SoundNotificationAction(
                        actionDescriptor,
                        action.getLoopInterval()));
        }
        else if (actionType.equals(ACTION_LOG_MESSAGE))
        {
            registerNotificationForEvent (eventType,
                new LogMessageNotificationAction(
                    LogMessageNotificationAction.INFO_LOG_TYPE));
        }
        else if (actionType.equals(ACTION_POPUP_MESSAGE))
        {
            registerNotificationForEvent (eventType,
                new PopupMessageNotificationAction(defaultMessage));
        }
        else if (actionType.equals(ACTION_COMMAND))
        {
            registerNotificationForEvent (eventType,
                new CommandNotificationAction(actionDescriptor));
        }
    }

    /**
     * Removes the <tt>EventNotification</tt> corresponding to the given
     * <tt>eventType</tt> from the table of registered event notifications.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registering it) to be removed.
     */
    public void removeEventNotification(String eventType)
    {
        notifications.remove(eventType);
        
        this.fireNotificationEventTypeEvent(
            EVENT_TYPE_REMOVED, eventType);
    }

    /**
     * Removes the given actionType from the list of actions registered for the
     * given <tt>eventType</tt>.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registering it) for which we'll remove the notification.
     * @param actionType the type of the action that is to be executed when the
     * specified event occurs (could be one of the ACTION_XXX fields).
     */
    public void removeEventNotificationAction(  String eventType,
                                                String actionType)
    {
        Notification notification
            = notifications.get(eventType);
        
        if(notification == null)
            return;

        NotificationAction action = notification.getAction(actionType);
        
        if(action == null)
            return;

        notification.removeAction(actionType);

        saveNotification(
            eventType, 
            action, 
            false, 
            false);

        fireNotificationActionTypeEvent(
            ACTION_REMOVED,
            eventType,
            action);
    }

    /**
     * Returns an iterator over a list of all events registered in this
     * notification service. Each line in the returned list consists of
     * a String, representing the name of the event (as defined by the plugin
     * that registered it).
     *   
     * @return an iterator over a list of all events registered in this
     * notifications service
     */
    public Iterable<String> getRegisteredEvents()
    {
        return Collections.unmodifiableSet(
            notifications.keySet());
    }

    /**
     * Returns the notification action corresponding to the given
     * <tt>eventType</tt> and <tt>actionType</tt>.
     * 
     * @param eventType the type of the event that we'd like to retrieve.
     * @param actionType the type of the action that we'd like to retrieve a
     * descriptor for.
     * @return the notification action of the action to be executed
     * when an event of the specified type has occurred.
     */
    public NotificationAction getEventNotificationAction(
                                                            String eventType,
                                                            String actionType)
    {
        Notification notification = notifications.get(eventType);

        if(notification == null)
            return null;

        return notification.getAction(actionType);
    }

    /**
     * Adds the given <tt>listener</tt> to the list of change listeners.
     * 
     * @param listener the listener that we'd like to register to listen for
     * changes in the event notifications stored by this service.
     */
    public void addNotificationChangeListener(
        NotificationChangeListener listener)
    {
        synchronized (changeListeners)
        {
            changeListeners.add(listener);
        }
    }

    /**
     * Removes the given <tt>listener</tt> from the list of change listeners.
     * 
     * @param listener the listener that we'd like to remove
     */
    public void removeNotificationChangeListener(
        NotificationChangeListener listener)
    {
        synchronized (changeListeners)
        {
            changeListeners.remove(listener);
        }
    }

    /**
     * Adds an object that executes the actual action of a notification action.
     * If the same action type is added twice, the last added wins.
     * 
     * @param handler The handler that executes the action.
     */
    public void addActionHandler(NotificationHandler handler)
    {
        if(handler == null)
            throw new IllegalArgumentException("handler cannot be null");

        synchronized(handlers)
        {
            handlers.put(handler.getActionType(), handler);
            if(handlers.size() == NUM_ACTIONS && notificationCache != null)
            {
                for(NotificationData event : notificationCache)
                    fireNotification(event);

                notificationCache.clear();
                notificationCache = null;
            }
        }
    }

    /**
     * Removes an object that executes the actual action of notification action.
     * @param actionType The handler type to remove.
     */
    public void removeActionHandler(String actionType)
    {
        if(actionType == null)
            throw new IllegalArgumentException("actionType cannot be null");

        synchronized(handlers)
        {
            handlers.remove(actionType);
        }
    }

    /**
     * Gets a list of handler for the specified action type.
     * 
     * @param actionType the type for which the list of handlers should be
     *            retrieved or <tt>null</tt> if all handlers shall be returned.
     */
    public Iterable<NotificationHandler> getActionHandlers(String actionType)
    {
        if (actionType != null)
        {
            NotificationHandler handler = handlers.get(actionType);
            Set<NotificationHandler> ret;

            if (handler == null)
                ret = Collections.emptySet();
            else
                ret = Collections.singleton(handler);
            return ret;
        }
        else
            return handlers.values();
    }

    /**
     * Executes a notification data object on the handlers.
     * @param data The notification data to act upon.
     */
    private void fireNotification(NotificationData data)
    {
        Notification notification = notifications.get(data.getEventType());
        if(notification == null || !notification.isActive())
            return;

        for(NotificationAction action : notification.getActions().values())
        {
            String actionType = action.getActionType();
            if(!action.isEnabled() || !handlers.containsKey(actionType))
                continue;

            NotificationHandler handler = handlers.get(actionType);
            if (actionType.equals(ACTION_POPUP_MESSAGE))
            {
                ((PopupMessageNotificationHandler) handler)
                    .popupMessage((PopupMessageNotificationAction) action,
                        data.getTitle(), data.getMessage(),
                        data.getIcon(), data.getTag());
            }
            else if (actionType.equals(ACTION_LOG_MESSAGE))
            {
                ((LogMessageNotificationHandler) handler)
                    .logMessage((LogMessageNotificationAction) action,
                        data.getMessage());
            }
            else if (actionType.equals(ACTION_SOUND))
            {
                SoundNotificationAction soundNotificationAction
                    = (SoundNotificationAction) action;
                if(soundNotificationAction.isSoundNotificationEnabled()
                    || soundNotificationAction.isSoundPlaybackEnabled()
                    || soundNotificationAction.isSoundPCSpeakerEnabled())
                {
                    ((SoundNotificationHandler) handler)
                        .start((SoundNotificationAction) action, data);
                }
            }
            else if (actionType.equals(ACTION_COMMAND))
            {
                ((CommandNotificationHandler) handler)
                    .execute(
                        (CommandNotificationAction)action,
                        data.getExtra());
            }
        }
    }

    /**
     * Stops a notification if notification is continuous, like playing sounds
     * in loop. Do nothing if there are no such events currently processing.
     *
     * @param data the data that has been returned when firing the event..
     */
    public void stopNotification(NotificationData data)
    {
        Iterable<NotificationHandler> soundHandlers
            = getActionHandlers(NotificationAction.ACTION_SOUND);

        // There could be no sound action handler for this event type
        if (soundHandlers != null)
        {
            for (NotificationHandler handler : soundHandlers)
            {
                if (handler instanceof SoundNotificationHandler)
                    ((SoundNotificationHandler) handler).stop(data);
            }
        }
    }

    /**
     * If there is a registered event notification of the given
     * <tt>eventType</tt> and the event notification is currently activated, the
     * list of registered actions is executed.
     * 
     * @param eventType the type of the event that we'd like to fire a
     *            notification for.
     * @param title the title of the given message
     * @param message the message to use if and where appropriate (e.g. with
     *            systray or log notification.)
     * @param icon the icon to show in the notification if and where appropriate
     * @param tag additional info to be used by the notification handler
     * 
     * @return An object referencing the notification. It may be used to stop a
     *         still running notification. Can be null if the eventType is
     *         unknown or the notification is not active.
     */
    public NotificationData fireNotification(
        String eventType,
        String title,
        String message,
        byte[] icon,
        Object tag)
    {
        return fireNotification(eventType,
                                title,
                                message,
                                null,
                                icon,
                                tag);
    }

    /**
     * If there is a registered event notification of the given
     * <tt>eventType</tt> and the event notification is currently activated, the
     * list of registered actions is executed.
     * 
     * @param eventType the type of the event that we'd like to fire a
     *            notification for.
     * @param title the title of the given message
     * @param message the message to use if and where appropriate (e.g. with
     *            systray or log notification.)
     * @param extra the extra data to pass (especially for Command execution)
     * @param icon the icon to show in the notification if and where appropriate
     * @param tag additional info to be used by the notification handler
     * 
     * @return An object referencing the notification. It may be used to stop a
     *         still running notification. Can be null if the eventType is
     *         unknown or the notification is not active.
     */
    public NotificationData fireNotification(
        String eventType,
        String title,
        String message,
        Map<String,String> extra,
        byte[] icon,
        Object tag)
    {
        Notification notification = notifications.get(eventType);
        if(notification == null || !notification.isActive())
            return null;

        NotificationData data = new NotificationData(eventType, title,
            message, extra, icon, tag);

        //cache the notification when the handlers are not yet ready
        if (notificationCache != null)
            notificationCache.add(data);
        else
            fireNotification(data);

        return data;
    }

    /**
     * If there is a registered event notification of the given
     * <tt>eventType</tt> and the event notification is currently activated, we
     * go through the list of registered actions and execute them.
     * 
     * @param eventType the type of the event that we'd like to fire a
     *            notification for.
     * 
     * @return An object referencing the notification. It may be used to stop a
     *         still running notification. Can be null if the eventType is
     *         unknown or the notification is not active.
     */
    public NotificationData fireNotification(String eventType)
    {
        return this.fireNotification(eventType, null, null, null, null, null);
    }

    /**
     * Saves the event notification given by these parameters through the
     * <tt>ConfigurationService</tt>.
     * 
     * @param eventType the name of the event
     * @param action the notification action to change
     * @param isActive is the event active
     * @param isDefault is it a default one
     */
    private void saveNotification(  String eventType,
                                    NotificationAction action,
                                    boolean isActive,
                                    boolean isDefault)
    {
        String eventTypeNodeName = null;
        String actionTypeNodeName = null;

        List<String> eventTypes = configService
                .getPropertyNamesByPrefix(NOTIFICATIONS_PREFIX, true);

        for (String eventTypeRootPropName : eventTypes)
        {
            String eType = configService.getString(eventTypeRootPropName);
            if(eType.equals(eventType))
                eventTypeNodeName = eventTypeRootPropName;
        }

        // If we didn't find the given event type in the configuration we save
        // it here.
        if(eventTypeNodeName == null)
        {
            eventTypeNodeName = NOTIFICATIONS_PREFIX
                                + ".eventType" 
                                + Long.toString(System.currentTimeMillis());
            
            configService.setProperty(eventTypeNodeName, eventType);
        }

        // if we set active/inactive for the whole event notification
        if(action == null)
        {
            configService.setProperty(
                eventTypeNodeName + ".active",
                Boolean.toString(isActive));
            return;
        }

        // Go through contained actions.
        String actionPrefix = eventTypeNodeName + ".actions";

        List<String> actionTypes = configService
                .getPropertyNamesByPrefix(actionPrefix, true);

        for (String actionTypeRootPropName : actionTypes)
        {
            String aType = configService.getString(actionTypeRootPropName);
            if(aType.equals(action.getActionType()))
                actionTypeNodeName = actionTypeRootPropName;
        }

        Map<String, Object> configProperties = new HashMap<String, Object>();

        // If we didn't find the given actionType in the configuration we save
        // it here.
        if(actionTypeNodeName == null)
        {
            actionTypeNodeName = actionPrefix
                                    + ".actionType"
                                    + Long.toString(System.currentTimeMillis());

            configProperties.put(actionTypeNodeName, action.getActionType());
        }

        if(action instanceof SoundNotificationAction)
        {
            SoundNotificationAction soundAction
                = (SoundNotificationAction) action;

            configProperties.put(
                actionTypeNodeName + ".soundFileDescriptor",
                soundAction.getDescriptor());

            configProperties.put(
                actionTypeNodeName + ".loopInterval",
                soundAction.getLoopInterval());

            configProperties.put(
                actionTypeNodeName + ".isSoundNotificationEnabled",
                soundAction.isSoundNotificationEnabled());

            configProperties.put(
                actionTypeNodeName + ".isSoundPlaybackEnabled",
                soundAction.isSoundPlaybackEnabled());

            configProperties.put(
                actionTypeNodeName + ".isSoundPCSpeakerEnabled",
                soundAction.isSoundPCSpeakerEnabled());
        }
        else if(action instanceof PopupMessageNotificationAction)
        {
            PopupMessageNotificationAction messageAction
                = (PopupMessageNotificationAction) action;

            configProperties.put(
                actionTypeNodeName + ".defaultMessage",
                messageAction.getDefaultMessage());
        }
        else if(action instanceof LogMessageNotificationAction)
        {
            LogMessageNotificationAction logMessageAction
                = (LogMessageNotificationAction) action;

            configProperties.put(
                actionTypeNodeName + ".logType",
                logMessageAction.getLogType());
        }
        else if(action instanceof CommandNotificationAction)
        {
            CommandNotificationAction commandAction
                = (CommandNotificationAction) action;

            configProperties.put(
                actionTypeNodeName + ".commandDescriptor",
                commandAction.getDescriptor());
        }

        configProperties.put(
            actionTypeNodeName + ".enabled",
            Boolean.toString(isActive));

        configProperties.put(
            actionTypeNodeName + ".default",
            Boolean.toString(isDefault));

        configService.setProperties(configProperties);
    }

    /**
     * Loads all previously saved event notifications.
     */
    private void loadNotifications()
    {
        List<String> eventTypes = configService
                .getPropertyNamesByPrefix(NOTIFICATIONS_PREFIX, true);

        for (String eventTypeRootPropName : eventTypes)
        {
            boolean isEventActive = 
                isEnabled(eventTypeRootPropName + ".active");

            String eventType
                = configService.getString(eventTypeRootPropName);

            List<String> actions = configService
                .getPropertyNamesByPrefix(
                    eventTypeRootPropName + ".actions", true);

            for (String actionPropName : actions)
            {
                String actionType = configService.getString(actionPropName);

                NotificationAction action = null;

                if(actionType.equals(ACTION_SOUND))
                {
                    String soundFileDescriptor
                        = configService.getString(
                            actionPropName + ".soundFileDescriptor");

                    String loopInterval
                        = configService.getString(
                            actionPropName + ".loopInterval");

                    boolean isSoundNotificationEnabled
                        = configService.getBoolean(
                            actionPropName + ".isSoundNotificationEnabled",
                            (soundFileDescriptor != null));

                    boolean isSoundPlaybackEnabled
                        = configService.getBoolean(
                            actionPropName + ".isSoundPlaybackEnabled",
                            false);

                    boolean isSoundPCSpeakerEnabled
                        = configService.getBoolean(
                            actionPropName + ".isSoundPCSpeakerEnabled",
                            false);

                    action = new SoundNotificationAction(
                        soundFileDescriptor,
                        Integer.parseInt(loopInterval),
                        isSoundNotificationEnabled,
                        isSoundPlaybackEnabled,
                        isSoundPCSpeakerEnabled);
                }
                else if(actionType.equals(ACTION_POPUP_MESSAGE))
                {
                    String defaultMessage
                        = configService.getString(
                            actionPropName + ".defaultMessage");

                    action = new PopupMessageNotificationAction(defaultMessage);
                }
                else if(actionType.equals(ACTION_LOG_MESSAGE))
                {
                    String logType
                        = configService.getString(
                            actionPropName + ".logType");

                    action = new LogMessageNotificationAction(logType);
                }
                else if(actionType.equals(ACTION_COMMAND))
                {
                    String commandDescriptor
                        = configService.getString(
                            actionPropName + ".commandDescriptor");

                    action = new CommandNotificationAction(commandDescriptor);
                }

                action.setEnabled(isEnabled(actionPropName + ".enabled"));

                // Load the data in the notifications table.
                Notification notification = notifications.get(eventType);
                if(notification == null)
                {
                    notification = new Notification(eventType);
                    notifications.put(eventType, notification);
                }
                notification.setActive(isEventActive);
                notification.addAction(action);
            }
        }
    }

    private boolean isEnabled(String configProperty)
    {
        Object isEnabledObj = configService.getProperty(configProperty);

        // if setting is missing we accept it is true 
        // this way we not affect old saved settings
        if(isEnabledObj == null)
            return true;
        else
            return Boolean.parseBoolean((String)isEnabledObj);
    }

    /**
     * Finds the <tt>EventNotification</tt> corresponding to the given
     * <tt>eventType</tt> and marks it as activated/deactivated.
     * 
     * @param eventType the name of the event, which actions should be activated
     * /deactivated. 
     * @param isActive indicates whether to activate or deactivate the actions
     * related to the specified <tt>eventType</tt>.
     */
    public void setActive(String eventType, boolean isActive)
    {
        Notification eventNotification
            = notifications.get(eventType);

        if(eventNotification == null)
            return;

        eventNotification.setActive(isActive);
        saveNotification(eventType, null, isActive, false);
    }

    /**
     * Finds the <tt>EventNotification</tt> corresponding to the given
     * <tt>eventType</tt> and returns its isActive status.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registered it) that we are checking.
     * @return <code>true</code> if actions for the specified <tt>eventType</tt>
     * are activated, <code>false</code> - otherwise. If the given
     * <tt>eventType</tt> is not contained in the list of registered event
     * types - returns <code>false</code>.
     */
    public boolean isActive(String eventType)
    {
        Notification eventNotification
            = notifications.get(eventType);

        if(eventNotification == null)
            return false;

        return eventNotification.isActive();
    }

    /**
     * Notifies all registered <tt>NotificationChangeListener</tt>s that a
     * <tt>NotificationEventTypeEvent</tt> has occurred.
     * 
     * @param eventType the type of the event, which is one of EVENT_TYPE_XXX
     * constants declared in the <tt>NotificationEventTypeEvent</tt> class.
     * @param sourceEventType the <tt>eventType</tt>, for which this event is
     * about
     */
    private void fireNotificationEventTypeEvent(String eventType,
                                                String sourceEventType)
    {
        if (logger.isDebugEnabled())
            logger.debug("Dispatching NotificationEventType Change. Listeners="
                     + changeListeners.size()
                     + " evt=" + eventType);

        NotificationEventTypeEvent event
            = new NotificationEventTypeEvent(this, eventType, sourceEventType);

        for (NotificationChangeListener listener : changeListeners)
        {
            if (eventType.equals(EVENT_TYPE_ADDED))
            {
                listener.eventTypeAdded(event);
            }
            else if (eventType.equals(EVENT_TYPE_REMOVED))
            {
                listener.eventTypeRemoved(event);
            }
        }
    }

    /**
     * Notifies all registered <tt>NotificationChangeListener</tt>s that a
     * <tt>NotificationActionTypeEvent</tt> has occurred.
     * 
     * @param eventType the type of the event, which is one of ACTION_XXX
     * constants declared in the <tt>NotificationActionTypeEvent</tt> class.
     * @param sourceEventType the <tt>eventType</tt>, which is the parent of the
     * action
     * @param action the notification action
     */
    private void fireNotificationActionTypeEvent(
                                        String eventType,
                                        String sourceEventType,
                                        NotificationAction action)
    {
        NotificationActionTypeEvent event
            = new NotificationActionTypeEvent(  this,
                                                eventType,
                                                sourceEventType,
                                                action);


        for(NotificationChangeListener listener : changeListeners)
        {
            if (eventType.equals(ACTION_ADDED))
            {
                listener.actionAdded(event);
            }
            else if (eventType.equals(ACTION_REMOVED))
            {
                listener.actionRemoved(event);
            }
            else if (eventType.equals(ACTION_CHANGED))
            {
                listener.actionChanged(event);
            }
        }
    }

    private boolean isDefault(String eventType, String actionType)
    {
        List<String> eventTypes = configService
                .getPropertyNamesByPrefix(NOTIFICATIONS_PREFIX, true);

        for (String eventTypeRootPropName : eventTypes)
        {
            String eType
                = configService.getString(eventTypeRootPropName);

            if(!eType.equals(eventType))
                continue;

            List<String> actions = configService
                .getPropertyNamesByPrefix(
                    eventTypeRootPropName + ".actions", true);

            for (String actionPropName : actions)
            {
                String aType
                    = configService.getString(actionPropName);

                if(!aType.equals(actionType))
                    continue;

                Object isDefaultdObj = 
                    configService.getProperty(actionPropName + ".default");

                // if setting is missing we accept it is true 
                // this way we override old saved settings
                if(isDefaultdObj == null)
                    return true;
                else
                    return Boolean.parseBoolean((String)isDefaultdObj);
            }
        }
        return true;
    }

    /**
     * Creates a new default <tt>EventNotification</tt> or obtains the
     * corresponding existing one and registers a new action in it.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registering it) that we are setting an action for.
     * @param action the <tt>NotificationAction</tt> to register
     */
    public void registerDefaultNotificationForEvent(
        String eventType,
        NotificationAction action)
    {
        if(isDefault(eventType, action.getActionType()))
        {
            NotificationAction h = 
                getEventNotificationAction(eventType,
                    action.getActionType());

            boolean isNew = false;
            if(h == null)
            {
                isNew = true;
                h = action;
            }

            this.saveNotification(  eventType,
                                    action,
                                    h.isEnabled(),
                                    true);

            Notification notification = null;

            if(notifications.containsKey(eventType))
                notification = notifications.get(eventType);
            else
            {
                notification = new Notification(eventType);
                notifications.put(eventType, notification);
            }

            notification.addAction(action);

            // We fire the appropriate event depending on whether this is an
            // already existing actionType or a new one.
            fireNotificationActionTypeEvent(
                isNew
                    ? ACTION_ADDED
                    : ACTION_CHANGED,
                eventType,
                action);
        }

        // now store this default events if we want to restore them
        Notification notification = null;

        if(defaultNotifications.containsKey(eventType))
            notification = defaultNotifications.get(eventType);
        else
        {
            notification = new Notification(eventType);
            
            defaultNotifications.put(eventType, notification);
        }

        notification.addAction(action);
    }

    /**
     * Creates a new default <tt>EventNotification</tt> or obtains the corresponding
     * existing one and registers a new action in it.
     * 
     * @param eventType the name of the event (as defined by the plugin that's
     * registering it) that we are setting an action for.
     * @param actionType the type of the action that is to be executed when the
     * specified event occurs (could be one of the ACTION_XXX fields).
     * @param actionDescriptor a String containing a description of the action
     * (a URI to the sound file for audio notifications or a command line for
     * exec action types) that should be executed when the action occurs.
     * @param defaultMessage the default message to use if no specific message
     * has been provided when firing the notification.
     */
    public void registerDefaultNotificationForEvent(    String eventType,
                                                        String actionType,
                                                        String actionDescriptor,
                                                        String defaultMessage)
    {
        if (logger.isDebugEnabled())
            logger.debug("Registering default event " + eventType + "/" + 
            actionType + "/" + actionDescriptor + "/" + defaultMessage);

        if(isDefault(eventType, actionType))
        {
            NotificationAction action = 
                getEventNotificationAction(eventType, actionType);
            boolean isNew = false;

            if(action == null)
            {
                isNew = true;

                if (actionType.equals(ACTION_SOUND))
                {
                    action = new SoundNotificationAction(actionDescriptor, -1);
                }
                else if (actionType.equals(ACTION_LOG_MESSAGE))
                {
                    action = new LogMessageNotificationAction(
                        LogMessageNotificationAction.INFO_LOG_TYPE);
                }
                else if (actionType.equals(ACTION_POPUP_MESSAGE))
                {
                    action = new PopupMessageNotificationAction(defaultMessage);
                }
                else if (actionType.equals(ACTION_COMMAND))
                {
                    action = new CommandNotificationAction(actionDescriptor);
                }
            }

            this.saveNotification(  eventType,
                                    action,
                                    action.isEnabled(),
                                    true);

            Notification notification = null;

            if(notifications.containsKey(eventType))
                notification = notifications.get(eventType);
            else
            {
                notification = new Notification(eventType);
                notifications.put(eventType, notification);
            }

            notification.addAction(action);

            // We fire the appropriate event depending on whether this is an
            // already existing actionType or a new one.
            fireNotificationActionTypeEvent(
                isNew
                    ? ACTION_ADDED
                    : ACTION_CHANGED,
                eventType,
                action);
        }

        // now store this default events if we want to restore them
        Notification notification = null;

        if(defaultNotifications.containsKey(eventType))
            notification = defaultNotifications.get(eventType);
        else
        {
            notification = new Notification(eventType);
            defaultNotifications.put(eventType, notification);
        }

        NotificationAction action = null;
        if (actionType.equals(ACTION_SOUND))
        {
            action = new SoundNotificationAction(actionDescriptor, -1);
        }
        else if (actionType.equals(ACTION_LOG_MESSAGE))
        {
            action = new LogMessageNotificationAction(
                LogMessageNotificationAction.INFO_LOG_TYPE);
        }
        else if (actionType.equals(ACTION_POPUP_MESSAGE))
        {
            action = new PopupMessageNotificationAction(defaultMessage);
        }
        else if (actionType.equals(ACTION_COMMAND))
        {
            action = new CommandNotificationAction(actionDescriptor);
        }

        notification.addAction(action);
    }

    /**
     * Deletes all registered events and actions 
     * and registers and saves the default events as current.
     */
    public void restoreDefaults()
    {
        for (String eventType : new Vector<String>(notifications.keySet()))
        {
            Notification notification = notifications.get(eventType);

            for (String actionType
                    : new Vector<String>(notification.getActions().keySet()))
                removeEventNotificationAction(eventType, actionType);

            removeEventNotification(eventType);
        }

        for (Map.Entry<String, Notification> entry
                : defaultNotifications.entrySet())
        {
            String eventType = entry.getKey();
            Notification notification = entry.getValue();

            for (NotificationAction action : notification.getActions().values())
                registerNotificationForEvent(eventType, action);
        }
    }
}
