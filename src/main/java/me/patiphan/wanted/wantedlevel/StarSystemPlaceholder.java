package me.patiphan.wanted.wantedlevel;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class StarSystemPlaceholder extends PlaceholderExpansion {

    private final StarSystem plugin;

    public StarSystemPlaceholder(StarSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        // This is required to persist the expansion
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getIdentifier() {
        return "starsystem";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "";
        }

        if (identifier.equalsIgnoreCase("stars")) {
            int stars = plugin.getStarLevel(player.getName());
            return getStarIcons(stars);
        }

        return null;
    }

    private String getStarIcons(int starCount) {
        StringBuilder stars = new StringBuilder();
        String filledStar = "§e✪";
        String emptyStar = "§7✪";

        for (int i = 0; i < starCount; i++) {
            stars.append(filledStar);
        }

        for (int i = starCount; i < 5; i++) {
            stars.append(emptyStar);
        }

        return stars.toString();
    }
}
