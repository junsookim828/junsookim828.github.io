package com.example.craitrainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HandBelief {
    private static final class State {
        final List<String> hand;
        final List<String> queue;

        State(List<String> hand, List<String> queue) {
            List<String> h = new ArrayList<>(hand);
            Collections.sort(h);
            this.hand = Collections.unmodifiableList(h);
            this.queue = Collections.unmodifiableList(new ArrayList<>(queue));
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof State)) return false;
            State other = (State) o;
            return hand.equals(other.hand) && queue.equals(other.queue);
        }

        @Override public int hashCode() { return Objects.hash(hand, queue); }
    }

    private final List<String> deck = new ArrayList<>();
    private Map<State, Long> weights = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();

    public HandBelief(List<String> cards) { setDeck(cards); }

    public synchronized void setDeck(List<String> cards) {
        if (cards.size() != 8 || cards.stream().distinct().count() != 8L) {
            throw new IllegalArgumentException("deck must contain 8 unique labels");
        }
        deck.clear();
        deck.addAll(cards);
        List<String> oldHistory = new ArrayList<>(history);
        history.clear();
        weights = new LinkedHashMap<>();
        permute(new ArrayList<>(cards), 0);
        for (String card : oldHistory) observe(card);
    }

    private void permute(List<String> a, int idx) {
        if (idx == a.size()) {
            State state = new State(a.subList(0, 4), a.subList(4, 8));
            weights.merge(state, 1L, Long::sum);
            return;
        }
        for (int i = idx; i < a.size(); i++) {
            Collections.swap(a, idx, i);
            permute(a, idx + 1);
            Collections.swap(a, idx, i);
        }
    }

    public synchronized boolean observe(String card) {
        history.add(card);
        Map<State, Long> next = new LinkedHashMap<>();
        for (Map.Entry<State, Long> entry : weights.entrySet()) {
            State state = entry.getKey();
            if (!state.hand.contains(card)) continue;
            List<String> hand = new ArrayList<>(state.hand);
            List<String> queue = new ArrayList<>(state.queue);
            hand.remove(card);
            String incoming = queue.remove(0);
            hand.add(incoming);
            queue.add(card);
            State ns = new State(hand, queue);
            next.merge(ns, entry.getValue(), Long::sum);
        }
        if (!next.isEmpty()) {
            weights = next;
            return true;
        }
        return false;
    }

    public synchronized Map<String, Double> probabilities() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String c : deck) out.put(c, 0.0);
        long total = 0L;
        for (long w : weights.values()) total += w;
        if (total == 0L) return out;
        for (Map.Entry<State, Long> entry : weights.entrySet()) {
            for (String c : entry.getKey().hand) {
                out.put(c, out.get(c) + ((double) entry.getValue() / total));
            }
        }
        return out;
    }

    public synchronized int hypothesisCount() { return weights.size(); }

    public synchronized void reset() {
        List<String> copy = new ArrayList<>(deck);
        history.clear();
        setDeck(copy);
    }
}
