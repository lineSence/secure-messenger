package dev.sm.messenger.diagnostics

/** ADR-150: только закрытый набор событий. API не принимает сообщения, исключения или строки. */
internal enum class DiagnosticEvent {
    AppStart, RelayNew, RelayTicket, RelayGuest, RelaySend, RelayFetch, RelayWait, RelayAck, RelayDelete, RelayPing,
    NetworkChange, ChannelRenew, RelayReceive, RelaySendLoop, RelayLoop, NodeOpen,
    InviteCreate, InviteOpen, InviteOpenCoded, InviteRevoke, InviteConfirm, InviteCancel, InviteFrame,
    InviteCard, InviteStart, InviteReply, InviteDecode, InviteGuest, InviteTicket, ContactAdded,
    ChatQueue, ChatGather, ChatEncrypt, ChatDecrypt, ChatFrame, ChatDelivered, ChatAck, ChatMailbox,
    ChatStore, BleReceive, BleSend, BleState,
    PairSearch, PairAdvertise, PairGatt, PairIntro,
}

internal enum class DiagnosticResult {
    Start, Ok, Empty, Failed, NoNode, NoPass, NoPeer, NoKey, NoNetwork, Known, Own, Full, Bad, Expired,
    WrongCode, AskCode, AskContact, NoMemory, Rejected, NoQueue, Timeout, Dns, Connect, NoRoute, Tls, Io,
    Http, Protocol, RateLimited, Storage, Corrupt, Keystore, Signal, Delivery, Worker, Duplicate, Ignored,
    Declined, NoTicket, NodeMismatch, Wifi, Mobile, Offline,
}
