package com.sandlotminecraft.teambridge;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Bukkit stand-ins for the consumer tests: a player and a console that record what they are sent. */
final class Fakes {

    private Fakes() {
    }

    /** What a fake sender was sent, and which commands a fake player ran. */
    static final class Record {
        final List<String> messages = new ArrayList<>();
        final List<String> performed = new ArrayList<>();
    }

    /** A player whose {@code hasPermission} is the given test. */
    static Player player(UUID id, Predicate<String> permission, Record record) {
        return (Player) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> Harness.name(id);
                    case "hasPermission" -> args[0] instanceof String node && permission.test(node);
                    case "sendMessage" -> {
                        record.messages.add((String) args[0]);
                        yield null;
                    }
                    case "performCommand" -> {
                        record.performed.add((String) args[0]);
                        yield true;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlayer[" + id + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A player whose permissions are the transient links the harness's LuckPerms port holds. */
    static Player linkedPlayer(Harness h, UUID id, Record record) {
        return player(id, node -> h.links.of(id).contains(node + " server=" + Harness.SERVER), record);
    }

    /** A command sender that is not a player (the console). */
    static CommandSender console(Record record) {
        return (CommandSender) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        record.messages.add((String) args[0]);
                        yield null;
                    }
                    case "hasPermission" -> true;
                    case "getName" -> "CONSOLE";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeConsole";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A plugin command with the given name, as plugin.yml declares it. */
    static Command command(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return false;
            }
        };
    }
}
