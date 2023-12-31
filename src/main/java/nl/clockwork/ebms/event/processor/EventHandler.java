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

import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.concurrent.Future;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.oasis_open.committees.ebxml_cppa.schema.cpp_cpa_2_0.DeliveryChannel;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.transaction.annotation.Transactional;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.val;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import nl.clockwork.ebms.EbMSMessageStatus;
import nl.clockwork.ebms.client.EbMSClient;
import nl.clockwork.ebms.client.EbMSHttpClientFactory;
import nl.clockwork.ebms.client.EbMSResponseException;
import nl.clockwork.ebms.client.EbMSUnrecoverableResponseException;
import nl.clockwork.ebms.cpa.CPAManager;
import nl.clockwork.ebms.cpa.CPAUtils;
import nl.clockwork.ebms.cpa.URLMapper;
import nl.clockwork.ebms.dao.EbMSDAO;
import nl.clockwork.ebms.encryption.EbMSMessageEncrypter;
import nl.clockwork.ebms.event.listener.EventListener;
import nl.clockwork.ebms.model.EbMSDocument;
import nl.clockwork.ebms.processor.EbMSMessageProcessor;
import nl.clockwork.ebms.processor.EbMSProcessingException;
import nl.clockwork.ebms.util.StreamUtils;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(transactionManager = "dataSourceTransactionManager")
class EventHandler
{
	@NonNull
	EventListener eventListener;
	@NonNull
	EbMSDAO ebMSDAO;
	@NonNull
	CPAManager cpaManager;
	@NonNull
	URLMapper urlMapper;
	@NonNull
	EventManager eventManager;
	@NonNull
	EbMSHttpClientFactory ebMSClientFactory;
	@NonNull
	EbMSMessageEncrypter messageEncrypter;
	@NonNull
	EbMSMessageProcessor messageProcessor;
	TimedTask timedTask;
	boolean deleteEbMSAttachmentsOnMessageProcessed;

	@Builder
	public EventHandler(
			@NonNull EventListener eventListener,
			@NonNull EbMSDAO ebMSDAO,
			@NonNull CPAManager cpaManager,
			@NonNull URLMapper urlMapper,
			@NonNull EventManager eventManager,
			@NonNull EbMSHttpClientFactory ebMSClientFactory,
			@NonNull EbMSMessageEncrypter messageEncrypter,
			@NonNull EbMSMessageProcessor messageProcessor,
			TimedTask timedTask,
			boolean deleteEbMSAttachmentsOnMessageProcessed)
	{
		this.eventListener = eventListener;
		this.ebMSDAO = ebMSDAO;
		this.cpaManager = cpaManager;
		this.urlMapper = urlMapper;
		this.eventManager = eventManager;
		this.ebMSClientFactory = ebMSClientFactory;
		this.messageEncrypter = messageEncrypter;
		this.messageProcessor = messageProcessor;
		this.deleteEbMSAttachmentsOnMessageProcessed = deleteEbMSAttachmentsOnMessageProcessed;
		this.timedTask = timedTask;
	}

	public void handle(EbMSEvent event)
	{
		Runnable runnable = () ->
		{
			if (event.getTimeToLive() == null || Instant.now().isBefore(event.getTimeToLive()))
				sendEvent(event);
			else
				expireEvent(event);
		};
		timedTask.run(runnable);
	}

	@Async("eventHandlerTaskExecutor")
	public Future<Void> handleAsync(EbMSEvent event)
	{
		Runnable runnable = () ->
		{
			if (event.getTimeToLive() == null || Instant.now().isBefore(event.getTimeToLive()))
				sendEvent(event);
			else
				expireEvent(event);
		};
		timedTask.run(runnable);
		return AsyncResult.forValue(null);
	}

	private void sendEvent(final EbMSEvent event)
	{
		val receiveDeliveryChannel = cpaManager.getDeliveryChannel(
				event.getCpaId(),
				event.getReceiveDeliveryChannelId())
					.orElseThrow(() -> StreamUtils.illegalStateException("ReceiveDeliveryChannel",event.getCpaId(),event.getReceiveDeliveryChannelId()));
		val url = urlMapper.getURL(CPAUtils.getUri(receiveDeliveryChannel));
		val requestDocument = ebMSDAO.getEbMSDocumentIfUnsent(event.getMessageId());
		if (!requestDocument.isPresent())
			eventManager.deleteEvent(event.getMessageId());
		requestDocument.ifPresent(d -> sendEvent(event,receiveDeliveryChannel,url,d));
	}

	private void sendEvent(EbMSEvent event, DeliveryChannel receiveDeliveryChannel, String url, EbMSDocument requestDocument)
	{
		try
		{
			sendMessage(event,receiveDeliveryChannel,url,requestDocument);
		}
		catch (final EbMSResponseException e)
		{
			log.error("",e);
			Runnable runnable = () ->
			{
				eventManager.updateEvent(event,url,EbMSEventStatus.FAILED,e.getMessage());
				if ((e instanceof EbMSUnrecoverableResponseException) || !CPAUtils.isReliableMessaging(receiveDeliveryChannel))
					if (ebMSDAO.updateMessage(event.getMessageId(),EbMSMessageStatus.CREATED,EbMSMessageStatus.DELIVERY_FAILED) > 0)
					{
						eventListener.onMessageFailed(event.getMessageId());
						if (deleteEbMSAttachmentsOnMessageProcessed)
							ebMSDAO.deleteAttachments(event.getMessageId());
					}
			};
			ebMSDAO.executeTransaction(runnable);
		}
		catch (final Exception e)
		{
			log.error("",e);
			Runnable runnable = () ->
			{
				eventManager.updateEvent(event,url,EbMSEventStatus.FAILED,ExceptionUtils.getStackTrace(e));
				if (!CPAUtils.isReliableMessaging(receiveDeliveryChannel))
					if (ebMSDAO.updateMessage(event.getMessageId(),EbMSMessageStatus.CREATED,EbMSMessageStatus.DELIVERY_FAILED) > 0)
					{
						eventListener.onMessageFailed(event.getMessageId());
						if (deleteEbMSAttachmentsOnMessageProcessed)
							ebMSDAO.deleteAttachments(event.getMessageId());
					}
			};
			ebMSDAO.executeTransaction(runnable);
		}
		finally
		{
			MDC.clear();
		}
	}

	private void sendMessage(final EbMSEvent event, DeliveryChannel receiveDeliveryChannel, final String url, EbMSDocument requestDocument)
	{
		try
		{
			if (event.isConfidential())
				messageEncrypter.encrypt(receiveDeliveryChannel,requestDocument);
			log.info("Sending message " + event.getMessageId() + " to " + url);
			val responseDocument = createClient(event).sendMessage(url,requestDocument);
			handleResponse(event,receiveDeliveryChannel,url,requestDocument,responseDocument);
			log.info("Message " + event.getMessageId() + " sent");
		}
		catch (CertificateException e)
		{
			throw new EbMSProcessingException(e);
		}
	}

	private void handleResponse(final EbMSEvent event, DeliveryChannel receiveDeliveryChannel, final String url, EbMSDocument requestDocument, final nl.clockwork.ebms.model.EbMSDocument responseDocument)
	{
		Runnable runnable = () ->
		{
			messageProcessor.processResponse(requestDocument,responseDocument);
			eventManager.updateEvent(event,url,EbMSEventStatus.SUCCEEDED);
			if (!CPAUtils.isReliableMessaging(receiveDeliveryChannel))
				if (ebMSDAO.updateMessage(event.getMessageId(),EbMSMessageStatus.CREATED,EbMSMessageStatus.DELIVERED) > 0)
				{
					eventListener.onMessageDelivered(event.getMessageId());
					if (deleteEbMSAttachmentsOnMessageProcessed)
						ebMSDAO.deleteAttachments(event.getMessageId());
				}
		};
		ebMSDAO.executeTransaction(runnable);
	}

	private EbMSClient createClient(EbMSEvent event) throws CertificateException
	{
		String cpaId = event.getCpaId();
		val sendDeliveryChannel = event.getSendDeliveryChannelId() != null ?
				cpaManager.getDeliveryChannel(cpaId,event.getSendDeliveryChannelId())
				.orElse(null) : null;
		return ebMSClientFactory.getEbMSClient(cpaId,sendDeliveryChannel);
	}

	private void expireEvent(final EbMSEvent event)
	{
		log.warn("Expiring message " +  event.getMessageId());
		Runnable runnable = () ->
		{
			log.warn("Expiring message " +  event.getMessageId());
			ebMSDAO.getEbMSDocumentIfUnsent(event.getMessageId()).ifPresent(d -> updateMessage(event.getMessageId()));
			eventManager.deleteEvent(event.getMessageId());
		};
		ebMSDAO.executeTransaction(runnable);
	}

	private void updateMessage(final String messageId)
	{
		if (ebMSDAO.updateMessage(messageId,EbMSMessageStatus.CREATED,EbMSMessageStatus.EXPIRED) > 0)
		{
			eventListener.onMessageExpired(messageId);
			if (deleteEbMSAttachmentsOnMessageProcessed)
				ebMSDAO.deleteAttachments(messageId);
		}
	}
}
