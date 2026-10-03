package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

final class Av1FirstFrameProbe {
   private final long startedNanos;
   private final long timeoutMillis;
   private final long timeoutNanos;
   private final int maxPackets;
   private final LongSupplier nanoClock;
   private Av1FirstFrameProbe.Outcome outcome = Av1FirstFrameProbe.Outcome.ACTIVE;
   private Av1FirstFrameProbe.Decision exhaustedDecision;
   private Av1FirstFrameProbe.PacketPhase packetPhase = Av1FirstFrameProbe.PacketPhase.NONE;
   private long nextPacketToken;
   private long activePacketToken;
   private int activePacketOrdinal;
   private int successfulPackets;
   private boolean deadlineLatched;
   private long nextFrameTicket;
   private long pendingFrameTicket;
   private long pendingFramePacketToken;
   private long pendingFrameElapsedNanos = -1L;

   Av1FirstFrameProbe(long startedNanos, long timeoutMillis, int maxPackets) {
      this(startedNanos, timeoutMillis, maxPackets, System::nanoTime);
   }

   Av1FirstFrameProbe(long startedNanos, long timeoutMillis, int maxPackets, LongSupplier nanoClock) {
      this.startedNanos = startedNanos;
      this.timeoutMillis = timeoutMillis;
      this.timeoutNanos = timeoutMillis > 0L ? TimeUnit.MILLISECONDS.toNanos(timeoutMillis) : 0L;
      this.maxPackets = maxPackets;
      this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
   }

   long startedNanos() {
      return this.startedNanos;
   }

   long timeoutMillis() {
      return this.timeoutMillis;
   }

   int maxPackets() {
      return this.maxPackets;
   }

   synchronized int successfulPackets() {
      return this.successfulPackets;
   }

   synchronized Av1FirstFrameProbe.PacketAdmission beginPacketNow() {
      Av1FirstFrameProbe.Decision terminal = this.terminalDecision();
      if (terminal != null) {
         return new Av1FirstFrameProbe.PacketAdmission(null, terminal);
      } else if (this.packetPhase == Av1FirstFrameProbe.PacketPhase.NONE && this.pendingFrameTicket <= 0L) {
         long elapsedNanos = this.elapsedNowLocked();
         if (this.timeExpired(elapsedNanos)) {
            this.exhaust(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED);
            return new Av1FirstFrameProbe.PacketAdmission(null, Av1FirstFrameProbe.Decision.TIME_EXHAUSTED);
         } else if (this.packetExhausted()) {
            this.exhaust(Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED);
            return new Av1FirstFrameProbe.PacketAdmission(null, Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED);
         } else {
            Av1FirstFrameProbe.PacketPermit permit = new Av1FirstFrameProbe.PacketPermit(++this.nextPacketToken, this.successfulPackets + 1);
            this.activePacketToken = permit.token();
            this.activePacketOrdinal = permit.ordinal();
            this.packetPhase = Av1FirstFrameProbe.PacketPhase.RESERVED;
            this.deadlineLatched = false;
            return new Av1FirstFrameProbe.PacketAdmission(permit, Av1FirstFrameProbe.Decision.CONTINUE);
         }
      } else {
         return new Av1FirstFrameProbe.PacketAdmission(null, this.currentActiveDecision());
      }
   }

   synchronized Av1FirstFrameProbe.PacketAdmission beginEndOfStreamDrainNow() {
      Av1FirstFrameProbe.Decision terminal = this.terminalDecision();
      if (terminal != null) {
         return new Av1FirstFrameProbe.PacketAdmission(null, terminal);
      } else if (this.packetPhase == Av1FirstFrameProbe.PacketPhase.NONE && this.pendingFrameTicket <= 0L) {
         long elapsedNanos = this.elapsedNowLocked();
         if (this.timeExpired(elapsedNanos)) {
            this.exhaust(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED);
            return new Av1FirstFrameProbe.PacketAdmission(null, Av1FirstFrameProbe.Decision.TIME_EXHAUSTED);
         } else {
            Av1FirstFrameProbe.PacketPermit permit = new Av1FirstFrameProbe.PacketPermit(++this.nextPacketToken, this.successfulPackets + 1);
            this.activePacketToken = permit.token();
            this.activePacketOrdinal = permit.ordinal();
            this.packetPhase = Av1FirstFrameProbe.PacketPhase.DRAINING;
            this.deadlineLatched = false;
            return new Av1FirstFrameProbe.PacketAdmission(permit, Av1FirstFrameProbe.Decision.CONTINUE);
         }
      } else {
         return new Av1FirstFrameProbe.PacketAdmission(null, this.currentActiveDecision());
      }
   }

   synchronized Av1FirstFrameProbe.PacketTransition markPacketSent(Av1FirstFrameProbe.PacketPermit permit) {
      if (this.matchesActivePermit(permit) && this.packetPhase == Av1FirstFrameProbe.PacketPhase.RESERVED) {
         this.successfulPackets++;
         this.packetPhase = Av1FirstFrameProbe.PacketPhase.DRAINING;
         this.notifyAll();
         return new Av1FirstFrameProbe.PacketTransition(true, this.decisionForState());
      } else {
         return new Av1FirstFrameProbe.PacketTransition(false, this.decisionForState());
      }
   }

   synchronized Av1FirstFrameProbe.PacketTransition endPacket(Av1FirstFrameProbe.PacketPermit permit, Av1FirstFrameProbe.PacketEnd end) {
      Objects.requireNonNull(end, "end");
      if (!this.matchesActivePermit(permit)) {
         return new Av1FirstFrameProbe.PacketTransition(false, this.decisionForState());
      } else if (end == Av1FirstFrameProbe.PacketEnd.DRAINED && this.packetPhase != Av1FirstFrameProbe.PacketPhase.DRAINING) {
         return new Av1FirstFrameProbe.PacketTransition(false, this.decisionForState());
      } else if (end == Av1FirstFrameProbe.PacketEnd.SEND_REJECTED && this.packetPhase != Av1FirstFrameProbe.PacketPhase.RESERVED) {
         return new Av1FirstFrameProbe.PacketTransition(false, this.decisionForState());
      } else if (end == Av1FirstFrameProbe.PacketEnd.DRAINED && this.pendingFrameTicket > 0L) {
         return new Av1FirstFrameProbe.PacketTransition(false, Av1FirstFrameProbe.Decision.FRAME_PENDING);
      } else {
         this.clearPacketLease();
         if (end == Av1FirstFrameProbe.PacketEnd.ABORTED && this.outcome == Av1FirstFrameProbe.Outcome.ACTIVE) {
            this.outcome = Av1FirstFrameProbe.Outcome.CANCELLED;
            this.clearPendingFrame();
         } else if (this.outcome == Av1FirstFrameProbe.Outcome.ACTIVE) {
            long elapsedNanos = this.elapsedNowLocked();
            if (this.deadlineLatched || this.timeExpired(elapsedNanos)) {
               this.exhaust(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED);
            } else if (end == Av1FirstFrameProbe.PacketEnd.DRAINED && this.packetExhausted()) {
               this.exhaust(Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED);
            }
         }

         this.deadlineLatched = false;
         this.notifyAll();
         return new Av1FirstFrameProbe.PacketTransition(true, this.decisionForState());
      }
   }

   synchronized Av1FirstFrameProbe.FramePreparation prepareFrame(Av1FirstFrameProbe.PacketPermit permit, long readyNanos) {
      Av1FirstFrameProbe.Decision terminal = this.terminalDecision();
      if (terminal != null) {
         return new Av1FirstFrameProbe.FramePreparation(-1L, terminal);
      } else if (!this.matchesActivePermit(permit) || this.packetPhase != Av1FirstFrameProbe.PacketPhase.DRAINING) {
         return new Av1FirstFrameProbe.FramePreparation(-1L, this.currentActiveDecision());
      } else if (this.pendingFrameTicket > 0L) {
         return new Av1FirstFrameProbe.FramePreparation(-1L, Av1FirstFrameProbe.Decision.FRAME_PENDING);
      } else {
         long readyElapsedNanos = this.elapsedAt(readyNanos);
         if (this.timeExpired(readyElapsedNanos)) {
            this.deadlineLatched = true;
            return new Av1FirstFrameProbe.FramePreparation(-1L, Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT);
         } else {
            this.pendingFrameTicket = ++this.nextFrameTicket;
            this.pendingFramePacketToken = permit.token();
            this.pendingFrameElapsedNanos = readyElapsedNanos;
            this.notifyAll();
            return new Av1FirstFrameProbe.FramePreparation(this.pendingFrameTicket, Av1FirstFrameProbe.Decision.FRAME_PENDING);
         }
      }
   }

   synchronized boolean commit(long ticket) {
      if (this.outcome == Av1FirstFrameProbe.Outcome.ACTIVE
         && ticket > 0L
         && ticket == this.pendingFrameTicket
         && this.pendingFramePacketToken == this.activePacketToken
         && this.packetPhase == Av1FirstFrameProbe.PacketPhase.DRAINING) {
         this.outcome = Av1FirstFrameProbe.Outcome.COMMITTED;
         this.clearPendingFrame();
         this.notifyAll();
         return true;
      } else {
         return false;
      }
   }

   synchronized boolean reject(long ticket) {
      if (this.outcome == Av1FirstFrameProbe.Outcome.ACTIVE
         && ticket > 0L
         && ticket == this.pendingFrameTicket
         && this.pendingFramePacketToken == this.activePacketToken
         && this.packetPhase == Av1FirstFrameProbe.PacketPhase.DRAINING) {
         this.clearPendingFrame();
         this.notifyAll();
         return true;
      } else {
         return false;
      }
   }

   synchronized void cancelPreparedFrame(long ticket) {
      this.reject(ticket);
   }

   synchronized Av1FirstFrameProbe.Decision awaitFrameDecision(long ticket, AtomicBoolean closed) throws InterruptedException {
      while (this.outcome == Av1FirstFrameProbe.Outcome.ACTIVE && this.pendingFrameTicket == ticket && ticket > 0L && !closed.get()) {
         this.wait(50L);
      }

      return this.decisionForState();
   }

   synchronized Av1FirstFrameProbe.Decision evaluateConsumerTimeNow() {
      Av1FirstFrameProbe.Decision terminal = this.terminalDecision();
      if (terminal != null) {
         return terminal;
      } else {
         long elapsedNanos = this.elapsedNowLocked();
         if (!this.timeExpired(elapsedNanos)) {
            return this.currentActiveDecision();
         } else if (this.packetPhase != Av1FirstFrameProbe.PacketPhase.NONE) {
            this.deadlineLatched = true;
            return this.pendingFrameTicket > 0L ? Av1FirstFrameProbe.Decision.FRAME_PENDING : Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT;
         } else {
            this.exhaust(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED);
            return Av1FirstFrameProbe.Decision.TIME_EXHAUSTED;
         }
      }
   }

   synchronized Av1FirstFrameProbe.Decision decision() {
      return this.decisionForState();
   }

   synchronized long pendingFrameElapsedNanos() {
      return this.pendingFrameElapsedNanos;
   }

   synchronized void cancel() {
      if (this.outcome != Av1FirstFrameProbe.Outcome.COMMITTED
         && this.outcome != Av1FirstFrameProbe.Outcome.EXHAUSTED
         && this.outcome != Av1FirstFrameProbe.Outcome.CANCELLED) {
         this.outcome = Av1FirstFrameProbe.Outcome.CANCELLED;
         this.clearPendingFrame();
         this.notifyAll();
      }
   }

   private Av1FirstFrameProbe.Decision currentActiveDecision() {
      if (this.pendingFrameTicket > 0L) {
         return Av1FirstFrameProbe.Decision.FRAME_PENDING;
      } else {
         return this.packetPhase != Av1FirstFrameProbe.PacketPhase.NONE ? Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT : Av1FirstFrameProbe.Decision.CONTINUE;
      }
   }

   private Av1FirstFrameProbe.Decision terminalDecision() {
      return switch (this.outcome) {
         case ACTIVE -> null;
         case COMMITTED -> Av1FirstFrameProbe.Decision.COMMITTED;
         case CANCELLED -> Av1FirstFrameProbe.Decision.CANCELLED;
         case EXHAUSTED -> this.exhaustedDecision;
      };
   }

   private Av1FirstFrameProbe.Decision decisionForState() {
      Av1FirstFrameProbe.Decision terminal = this.terminalDecision();
      return terminal != null ? terminal : this.currentActiveDecision();
   }

   private boolean matchesActivePermit(Av1FirstFrameProbe.PacketPermit permit) {
      return permit != null
         && this.packetPhase != Av1FirstFrameProbe.PacketPhase.NONE
         && permit.token() == this.activePacketToken
         && permit.ordinal() == this.activePacketOrdinal;
   }

   private boolean packetExhausted() {
      return this.maxPackets > 0 && this.successfulPackets >= this.maxPackets;
   }

   private boolean timeExpired(long elapsedNanos) {
      return this.timeoutNanos > 0L && elapsedNanos >= this.timeoutNanos;
   }

   private long elapsedNowLocked() {
      return this.elapsedAt(this.nanoClock.getAsLong());
   }

   private long elapsedAt(long nowNanos) {
      return Math.max(0L, nowNanos - this.startedNanos);
   }

   private void exhaust(Av1FirstFrameProbe.Decision decision) {
      this.outcome = Av1FirstFrameProbe.Outcome.EXHAUSTED;
      this.exhaustedDecision = decision;
      this.clearPendingFrame();
      this.notifyAll();
   }

   private void clearPacketLease() {
      this.packetPhase = Av1FirstFrameProbe.PacketPhase.NONE;
      this.activePacketToken = 0L;
      this.activePacketOrdinal = 0;
   }

   private void clearPendingFrame() {
      this.pendingFrameTicket = 0L;
      this.pendingFramePacketToken = 0L;
      this.pendingFrameElapsedNanos = -1L;
   }

   static enum Decision {
      CONTINUE,
      DRAIN_IN_FLIGHT,
      FRAME_PENDING,
      COMMITTED,
      CANCELLED,
      TIME_EXHAUSTED,
      PACKET_EXHAUSTED;
   }

   record FramePreparation(long ticket, Av1FirstFrameProbe.Decision decision) {
      boolean hasTicket() {
         return this.ticket > 0L && this.decision == Av1FirstFrameProbe.Decision.FRAME_PENDING;
      }
   }

   private static enum Outcome {
      ACTIVE,
      COMMITTED,
      CANCELLED,
      EXHAUSTED;
   }

   record PacketAdmission(Av1FirstFrameProbe.PacketPermit permit, Av1FirstFrameProbe.Decision decision) {
      boolean admitted() {
         return this.permit != null && this.decision == Av1FirstFrameProbe.Decision.CONTINUE;
      }

      boolean bypassedAfterCommit() {
         return this.permit == null && this.decision == Av1FirstFrameProbe.Decision.COMMITTED;
      }
   }

   static enum PacketEnd {
      DRAINED,
      SEND_REJECTED,
      ABORTED;
   }

   record PacketPermit(long token, int ordinal) {
      PacketPermit(long token, int ordinal) {
         if (token > 0L && ordinal > 0) {
            this.token = token;
            this.ordinal = ordinal;
         } else {
            throw new IllegalArgumentException("invalid packet permit");
         }
      }
   }

   private static enum PacketPhase {
      NONE,
      RESERVED,
      DRAINING;
   }

   record PacketTransition(boolean applied, Av1FirstFrameProbe.Decision decision) {
   }
}
