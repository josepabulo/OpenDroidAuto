#include <transport/TCPTransport.hpp>
#include <Log.h>

namespace aasdk
{
namespace transport
{

TCPTransport::TCPTransport(aasdk::io::ioService& ioService, tcp::ITCPEndpoint::Pointer tcpEndpoint)
    : Transport(ioService)
    , tcpEndpoint_(std::move(tcpEndpoint))
{

}

void TCPTransport::enqueueReceive(common::DataBuffer buffer)
{
    if (Log::isVerbose()) Log_v("enqueueReceive");
    auto receivePromise = tcp::ITCPEndpoint::Promise::defer(receiveStrand_, "TCPTransport_enqueueReceive");
    receivePromise->then([this, self = this->shared_from_this()](auto bytesTransferred) {
            this->receiveHandler(bytesTransferred);
        },
        [this, self = this->shared_from_this()](auto e) {
            this->rejectReceivePromises(e);
        });

    tcpEndpoint_->receive(buffer, std::move(receivePromise));
}

void TCPTransport::enqueueSend(SendQueue::iterator queueElement)
{
    auto sendPromise = tcp::ITCPEndpoint::Promise::defer(sendStrand_, "TCPTransport_enqueueSend");

    sendPromise->then([this, self = this->shared_from_this(), queueElement](auto) {
        this->sendHandler(queueElement, error::Error());
    },
    [this, self = this->shared_from_this(), queueElement](auto e) {
        this->sendHandler(queueElement, e);
    });

	if (Log::isDebug()) Log_d("send to tcpendpoint");
    tcpEndpoint_->send(common::DataConstBuffer(queueElement->first), std::move(sendPromise));
}

void TCPTransport::stop()
{
    if (Log::isDebug()) Log_d("stop tcp endpoint");
    if (isStopping_.exchange(true)) {
        return;
    }

    // Cancel pending socket async I/O first, then drain queues on their own strands
    // without destroying the boost::optional strands while workers may still use them.
    if (tcpEndpoint_) {
        tcpEndpoint_->stop();
    }

    if (sendStrand_) {
        sendStrand_->dispatch([this, self = this->shared_from_this()]() {
            for (auto& element : sendQueue_) {
                if (element.second) {
                    element.second->reject(error::Error(error::ErrorCode::OPERATION_ABORTED));
                }
            }
            sendQueue_.clear();
        });
    }

    if (receiveStrand_) {
        receiveStrand_->dispatch([this, self = this->shared_from_this()]() {
            this->rejectReceivePromises(error::Error(error::ErrorCode::OPERATION_ABORTED));
        });
    }
}

void TCPTransport::sendHandler(SendQueue::iterator queueElement, const error::Error& e)
{
    if (isStopping_) {
        return;
    }

    if(!e)
    {
        queueElement->second->resolve();
    }
    else
    {
        queueElement->second->reject(e);
    }

    sendQueue_.erase(queueElement);

    if(!sendQueue_.empty() && !isStopping_)
    {
        this->enqueueSend(sendQueue_.begin());
    }
}

}
}
