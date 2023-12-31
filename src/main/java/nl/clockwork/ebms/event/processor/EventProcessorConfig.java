/**
 * Copyright 2011 Clockwork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.clockwork.ebms.event.processor;

import javax.jms.ConnectionFactory;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import lombok.AccessLevel;
import lombok.val;
import lombok.experimental.FieldDefaults;
import nl.clockwork.ebms.client.EbMSHttpClientFactory;
import nl.clockwork.ebms.cpa.CPAManager;
import nl.clockwork.ebms.cpa.URLMapper;
import nl.clockwork.ebms.dao.EbMSDAO;
import nl.clockwork.ebms.encryption.EbMSMessageEncrypter;
import nl.clockwork.ebms.event.listener.EventListener;
import nl.clockwork.ebms.processor.EbMSMessageProcessor;

@Configuration
@EnableAsync
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EventProcessorConfig
{
	public enum EventProcessorType
	{
		DEFAULT, JMS;
	}
	@Autowired
	EbMSEventDAO ebMSEventDAO;
	@Autowired
	CPAManager cpaManager;
	@Value("${ebms.serverId}")
	String serverId;
	@Value("${eventProcessor.jms.destinationName}")
	String jmsDestinationName;
	@Value("${eventProcessor.jms.receiveTimeout}")
	long receiveTimeout;
	@Value("${eventProcessor.start}")
	boolean startEventProcessor;
	@Value("${eventProcessor.minThreads}")
	int minThreads;
	@Value("${eventProcessor.maxThreads}")
	int maxThreads;
	@Value("${eventProcessor.maxEvents}")
	int maxEvents;
	@Value("${eventProcessor.executionInterval}")
	int eventProcessorExecutionInterval;
	@Value("${eventHandlerTask.executionInterval}")
	int eventHandlerTaskExecutionInterval;
	@Autowired
	EventListener eventListener;
	@Autowired
	EbMSDAO ebMSDAO;
	@Autowired
	URLMapper urlMapper;
	@Autowired
	EventManager eventManager;
	@Autowired
	EbMSHttpClientFactory ebMSClientFactory;
	@Autowired
	EbMSMessageEncrypter messageEncrypter;
	@Autowired
	EbMSMessageProcessor messageProcessor;
	@Value("${ebmsMessage.deleteContentOnProcessed}")
	boolean deleteEbMSAttachmentsOnMessageProcessed;
	@Autowired
	ConnectionFactory connectionFactory;
	@Autowired
	@Qualifier("jmsTransactionManager")
	PlatformTransactionManager jmsTransactionManager;
	@Value("${eventProcessor.jms.destinationName}")
	String destinationName;

	@Bean("eventHandlerTaskExecutor")
	@Conditional(DefaultEventProcessor.class)
	public ThreadPoolTaskExecutor defaultEventProcessor()
	{
		val result = new ThreadPoolTaskExecutor();
		result.setCorePoolSize(minThreads);
		result.setMaxPoolSize(maxThreads);
		result.setQueueCapacity(maxEvents);
		result.setWaitForTasksToCompleteOnShutdown(true);
		return result;
	}

	@Bean
	@Conditional(DefaultEventProcessor.class)
	public EventTaskExecutor eventTaskExecutor()
	{
		return EventTaskExecutor.builder()
				.ebMSEventDAO(ebMSEventDAO)
				.eventHandler(eventHandler())
				.timedTask(new TimedTask(eventProcessorExecutionInterval))
				.maxEvents(maxEvents)
				.serverId(serverId)
				.build();
	}

	@Bean
	@Conditional(JmsEventProcessor.class)
	public DefaultMessageListenerContainer jmsEventProcessor()
	{
		val result = new DefaultMessageListenerContainer();
		result.setConnectionFactory(connectionFactory);
		result.setTransactionManager(jmsTransactionManager);
		result.setSessionTransacted(true);
		result.setConcurrentConsumers(minThreads);
		result.setMaxConcurrentConsumers(maxThreads);
		result.setReceiveTimeout(receiveTimeout);
		result.setDestinationName(StringUtils.isEmpty(jmsDestinationName) ? JMSEventManager.JMS_DESTINATION_NAME : jmsDestinationName);
		result.setMessageListener(new EbMSSendEventListener(eventHandler()));
		return result;
	}

	@Bean
  public EventHandler eventHandler()
	{
		return EventHandler.builder()
				.eventListener(eventListener)
				.ebMSDAO(ebMSDAO)
				.cpaManager(cpaManager)
				.urlMapper(urlMapper)
				.eventManager(eventManager)
				.ebMSClientFactory(ebMSClientFactory)
				.messageEncrypter(messageEncrypter)
				.messageProcessor(messageProcessor)
				.timedTask(new TimedTask(eventHandlerTaskExecutionInterval))
				.deleteEbMSAttachmentsOnMessageProcessed(deleteEbMSAttachmentsOnMessageProcessed)
				.build();
	}

	public static class DefaultEventProcessor implements Condition
	{
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata)
		{
			return context.getEnvironment().getProperty("eventProcessor.start",Boolean.class,true)
					&& context.getEnvironment().getProperty("eventProcessor.type",EventProcessorType.class,EventProcessorType.DEFAULT) == EventProcessorType.DEFAULT;
		}
	}
	public static class JmsEventProcessor implements Condition
	{
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata)
		{
			return context.getEnvironment().getProperty("eventProcessor.start",Boolean.class,true)
					&& context.getEnvironment().getProperty("eventProcessor.type",EventProcessorType.class,EventProcessorType.DEFAULT) == EventProcessorType.JMS;
		}
	}
	public static class DefaultEventProcessorType implements Condition
	{
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata)
		{
			return context.getEnvironment().getProperty("eventProcessor.type",EventProcessorType.class,EventProcessorType.DEFAULT) == EventProcessorType.DEFAULT;
		}
	}
	public static class JmsEventProcessorType implements Condition
	{
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata)
		{
			return context.getEnvironment().getProperty("eventProcessor.type",EventProcessorType.class,EventProcessorType.DEFAULT) == EventProcessorType.JMS;
		}
	}
}
