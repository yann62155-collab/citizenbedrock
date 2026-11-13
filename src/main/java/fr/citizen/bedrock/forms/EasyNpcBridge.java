package fr.citizen.bedrock.forms;

import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Dialogues des PNJ Easy NPC pour les joueurs console : le texte du PNJ et ses boutons
 * sont affichés dans un menu Bedrock ; chaque bouton exécute exactement la même action que sur PC
 * (autre dialogue, commerce, commande, téléportation...). Aucune modification d'Easy NPC.
 */
public final class EasyNpcBridge {
    private static final String DIALOG_MENU = "de.markusbordihn.easynpc.menu.dialog.DialogMenu";
    private static final String BUTTON_ACTION = "de.markusbordihn.easynpc.network.message.server.ExecuteDialogButtonActionMessage";

    private EasyNpcBridge() {
    }

    /** true si le menu était un dialogue Easy NPC et qu'il a été affiché en menu Bedrock. */
    public static boolean tryShow(ServerPlayer player, AbstractContainerMenu menu) {
        if (!isDialogMenu(menu.getClass())) {
            return false;
        }
        try {
            Object screenData = call(menu, "getScreenData");
            UUID npcId = (UUID) call(screenData, "uuid");
            UUID dialogId = (UUID) call(screenData, "dialogId");
            Object npc = call(menu, "getEasyNPC");
            if (npc == null || dialogId == null) return false;
            Object dialogSet = call(npc, "getDialogDataSet");
            Object dialog = dialogSet.getClass().getMethod("getDialog", UUID.class).invoke(dialogSet, dialogId);
            if (dialog == null) return false;

            String npcName = ((net.minecraft.world.entity.Entity) call(npc, "getEntity")).getName().getString();
            String text = String.valueOf(call(dialog, "getText"))
                    .replace("@npc", npcName).replace("@initiator", player.getName().getString())
                    .replace("@player", player.getName().getString());

            List<Object> buttons = new ArrayList<>((Collection<?>) call(dialog, "getDialogButtons"));
            BedrockForms.Simple form = BedrockForms.simple(npcName, text);
            for (Object button : buttons) {
                Component label = (Component) button.getClass().getMethod("getButtonName", int.class).invoke(button, 64);
                form.button(label.getString());
            }
            form.onClick(i -> {
                try {
                    UUID buttonId = (UUID) call(buttons.get(i), "id");
                    Class<?> actionClass = Class.forName(BUTTON_ACTION);
                    Constructor<?> ctor = actionClass.getConstructor(UUID.class, UUID.class, UUID.class);
                    Object action = ctor.newInstance(npcId, dialogId, buttonId);
                    actionClass.getMethod("handleServer", ServerPlayer.class).invoke(action, player);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    CitizenBedrock.LOGGER.error("[Citizen Bedrock] Action de dialogue Easy NPC impossible", e);
                }
            }).onClose(() -> {
                if (player.containerMenu == menu) player.closeContainer();
            });
            if (buttons.isEmpty()) {
                form.button("Au revoir");
                form.onClick(i -> {
                    if (player.containerMenu == menu) player.closeContainer();
                });
            }
            return form.send(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Lecture du dialogue Easy NPC impossible", e);
            return false;
        }
    }

    private static boolean isDialogMenu(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(DIALOG_MENU)) return true;
        }
        return false;
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = findMethod(target.getClass(), method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Method findMethod(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
        }
        for (Class<?> i : allInterfaces(c)) {
            for (Method m : i.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static List<Class<?>> allInterfaces(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) collect(i, out);
        }
        return out;
    }

    private static void collect(Class<?> i, List<Class<?>> out) {
        if (out.contains(i)) return;
        out.add(i);
        for (Class<?> p : i.getInterfaces()) collect(p, out);
    }
}
