-- ============================================================
--  MPVRX AUTOMATIC PROFILE SELECTOR
--
--  Works with the matching mpv.conf provided below.
--
--  Automatically selects:
--
--  - device-low
--  - device-balanced
--  - device-high
--  - battery-saver
--  - live-stream
--  - high-bitrate-stream
--
--  Main priorities:
--
--  1. Smooth playback
--  2. Battery efficiency
--  3. Safe RAM usage
--  4. Automatic device-specific cache selection
--  5. Automatic stream-type detection
--
--  This script never enables disk caching.
-- ============================================================


-- ============================================================
--  CONFIGURATION
-- ============================================================

-- Devices reporting less than 4500 MiB RAM use device-low.
--
-- This normally includes:
-- - 2 GB devices
-- - 3 GB devices
-- - Most 4 GB devices
local LOW_MEMORY_LIMIT_MB = 4500


-- Devices reporting at least 6500 MiB RAM use device-high.
--
-- This allows most phones marketed with 8 GB RAM, including
-- the Pixel 8, to receive device-high despite reserved memory.
local HIGH_MEMORY_LIMIT_MB = 6500


-- Devices between the two limits use device-balanced.
--
-- Typical classification:
--
-- Below 4500 MiB  = device-low
-- 4500-6499 MiB   = device-balanced
-- 6500 MiB+       = device-high


-- Enable battery saver at or below this percentage when the
-- device is not charging.
local LOW_BATTERY_PERCENT = 20


-- Disable battery saver after reaching this percentage or
-- connecting the device to power.
local BATTERY_RECOVERY_PERCENT = 25


-- A network stream at or above approximately 15 Mbps may use
-- high-bitrate-stream.
--
-- It is only applied to device-high.
local HIGH_BITRATE_THRESHOLD = 15000000


-- Wait this long after file-loaded before examining duration,
-- bitrate, seekability and stream properties.
local FILE_ANALYSIS_DELAY = 1.5


-- Duration of the initial "Analyzing media" message.
local ANALYZING_MESSAGE_DURATION = 1.5


-- Duration of the completed automatic-profile result.
--
-- The result remains visible for three seconds.
local RESULT_MESSAGE_DURATION = 3.0


-- Show automatic profile messages.
--
-- Change to false later to make profile selection silent.
local SHOW_PROFILE_MESSAGES = true



-- ============================================================
--  INTERNAL STATE
-- ============================================================

-- Safest default until RAM detection completes.
local base_device_profile = "device-low"


-- Description of the active profile combination.
local active_profile_description = nil


-- Automatic battery-saver state.
local battery_saver_active = false


-- Current media classification.
local current_file_is_network = false
local current_file_is_live = false
local current_file_is_high_bitrate = false


-- Delayed analysis timer.
local analysis_timer = nil



-- ============================================================
--  BASIC HELPERS
-- ============================================================

local function log(message)
    mp.msg.info(
        "[auto-profile] " .. tostring(message)
    )
end


local function show_message(message, duration)
    if not SHOW_PROFILE_MESSAGES then
        return
    end

    -- mp.osd_message duration is expressed in seconds.
    --
    -- Using mpv's own OSD gives us direct control over how long
    -- the message remains visible.
    mp.osd_message(
        tostring(message),
        tonumber(duration) or RESULT_MESSAGE_DURATION
    )
end


local function get_number_property(name)
    local value = mp.get_property_native(name)

    if value == nil then
        return nil
    end

    return tonumber(value)
end


local function value_to_boolean(value)
    if value == true then
        return true
    end

    if value == false then
        return false
    end

    if value == nil then
        return nil
    end

    local text = string.lower(
        tostring(value)
    )

    if
        text == "yes" or
        text == "true" or
        text == "1" or
        text == "charging" or
        text == "plugged"
    then
        return true
    end

    if
        text == "no" or
        text == "false" or
        text == "0" or
        text == "discharging" or
        text == "unplugged"
    then
        return false
    end

    return nil
end


local function get_boolean_property(name)
    return value_to_boolean(
        mp.get_property_native(name)
    )
end



-- ============================================================
--  RAM DETECTION
--
--  Android normally exposes total physical memory through:
--
--      /proc/meminfo
--
--  MemTotal is reported in KiB and converted here to MiB.
-- ============================================================

local function get_total_memory_mb()
    local success, result = pcall(function()
        local file = io.open(
            "/proc/meminfo",
            "r"
        )

        if file == nil then
            return nil
        end

        for line in file:lines() do
            local total_kb = line:match(
                "^MemTotal:%s+(%d+)%s+kB"
            )

            if total_kb ~= nil then
                file:close()

                return math.floor(
                    tonumber(total_kb) / 1024
                )
            end
        end

        file:close()
        return nil
    end)

    if not success then
        log(
            "RAM detection failed: " ..
            tostring(result)
        )

        return nil
    end

    return result
end



-- ============================================================
--  DEVICE PROFILE DETECTION
-- ============================================================

local function detect_device_profile()
    local total_memory_mb =
        get_total_memory_mb()

    if total_memory_mb == nil then
        -- Use the safest profile if Android blocks access.
        return "device-low", nil
    end

    if total_memory_mb < LOW_MEMORY_LIMIT_MB then
        return "device-low", total_memory_mb
    end

    if total_memory_mb < HIGH_MEMORY_LIMIT_MB then
        return "device-balanced", total_memory_mb
    end

    return "device-high", total_memory_mb
end


local function initialize_device_profile()
    local detected_profile, total_memory_mb =
        detect_device_profile()

    base_device_profile = detected_profile

    if total_memory_mb ~= nil then
        log(
            "Detected total RAM: " ..
            tostring(total_memory_mb) ..
            " MiB"
        )
    else
        log(
            "RAM unavailable; using device-low fallback"
        )
    end

    log(
        "Selected device profile: " ..
        tostring(base_device_profile)
    )
end



-- ============================================================
--  PROFILE APPLICATION
--
--  Profiles are applied in this order:
--
--  1. Base device profile
--  2. Battery saver, when active
--  3. Live-stream profile, when applicable
--  4. High-bitrate profile, when applicable
--
--  Reapplying the base profile first prevents settings from a
--  previous video remaining active.
-- ============================================================

local function apply_profile(name)
    local success, error_message = pcall(function()
        mp.commandv(
            "apply-profile",
            tostring(name)
        )
    end)

    if not success then
        log(
            "Could not apply profile '" ..
            tostring(name) ..
            "': " ..
            tostring(error_message)
        )

        return false
    end

    log(
        "Applied profile: " ..
        tostring(name)
    )

    return true
end


local function apply_current_profiles()
    apply_profile(base_device_profile)

    local applied_profiles = {
        base_device_profile
    }

    if battery_saver_active then
        apply_profile("battery-saver")

        table.insert(
            applied_profiles,
            "battery-saver"
        )
    end

    if current_file_is_live then
        apply_profile("live-stream")

        table.insert(
            applied_profiles,
            "live-stream"
        )

    elseif
        current_file_is_high_bitrate and
        not battery_saver_active
    then
        apply_profile("high-bitrate-stream")

        table.insert(
            applied_profiles,
            "high-bitrate-stream"
        )
    end

    active_profile_description =
        table.concat(applied_profiles, " + ")
end



-- ============================================================
--  NETWORK DETECTION
-- ============================================================

local function path_looks_network_based(path)
    if path == nil or path == "" then
        return false
    end

    local lower_path =
        string.lower(tostring(path))

    local network_protocols = {
        "^https?://",
        "^rtmp://",
        "^rtmps://",
        "^rtsp://",
        "^mms://",
        "^srt://",
        "^udp://",
        "^tcp://",
        "^ftp://",
        "^ftps://",
        "^smb://",
        "^dav://",
        "^davs://",
        "^webdav://",
        "^webdavs://",
        "^ytdl://"
    }

    for _, pattern in ipairs(network_protocols) do
        if lower_path:match(pattern) ~= nil then
            return true
        end
    end

    return false
end


local function detect_network_media()
    local original_path =
        mp.get_property("path", "")

    local resolved_path =
        mp.get_property(
            "stream-open-filename",
            ""
        )

    return
        path_looks_network_based(original_path) or
        path_looks_network_based(resolved_path)
end



-- ============================================================
--  LIVE-STREAM DETECTION
-- ============================================================

local function detect_live_stream()
    if not current_file_is_network then
        return false
    end

    local duration =
        get_number_property("duration")

    local seekable =
        get_boolean_property("seekable")

    local path = string.lower(
        mp.get_property("path", "")
    )

    local resolved_path = string.lower(
        mp.get_property(
            "stream-open-filename",
            ""
        )
    )

    local file_format = string.lower(
        mp.get_property("file-format", "")
    )

    -- A network source with no duration and no seek support is
    -- normally live.
    if duration == nil and seekable == false then
        return true
    end

    local is_hls =
        path:find(".m3u8", 1, true) ~= nil or
        resolved_path:find(".m3u8", 1, true) ~= nil or
        file_format == "hls"

    if is_hls and duration == nil then
        return true
    end

    local is_dedicated_live_protocol =
        path:match("^rtmp") ~= nil or
        path:match("^rtsp") ~= nil or
        path:match("^udp") ~= nil or
        path:match("^srt") ~= nil

    if
        is_dedicated_live_protocol and
        duration == nil
    then
        return true
    end

    return false
end



-- ============================================================
--  BITRATE DETECTION
-- ============================================================

local function get_selected_tracks_bitrate()
    local track_list =
        mp.get_property_native("track-list")

    if type(track_list) ~= "table" then
        return nil
    end

    local total_bitrate = 0
    local found_bitrate = false

    for _, track in ipairs(track_list) do
        if
            type(track) == "table" and
            track.selected == true and
            (
                track.type == "video" or
                track.type == "audio"
            )
        then
            local track_bitrate = tonumber(
                track["demux-bitrate"] or
                track.bitrate
            )

            if
                track_bitrate ~= nil and
                track_bitrate > 0
            then
                total_bitrate =
                    total_bitrate + track_bitrate

                found_bitrate = true
            end
        end
    end

    if found_bitrate then
        return total_bitrate
    end

    return nil
end


local function get_stream_bitrate()
    local selected_bitrate =
        get_selected_tracks_bitrate()

    if
        selected_bitrate ~= nil and
        selected_bitrate > 0
    then
        return selected_bitrate
    end

    local video_bitrate =
        get_number_property("video-bitrate")

    local audio_bitrate =
        get_number_property("audio-bitrate")

    local direct_total = 0
    local direct_found = false

    if video_bitrate ~= nil and video_bitrate > 0 then
        direct_total = direct_total + video_bitrate
        direct_found = true
    end

    if audio_bitrate ~= nil and audio_bitrate > 0 then
        direct_total = direct_total + audio_bitrate
        direct_found = true
    end

    if direct_found then
        return direct_total
    end

    -- Estimate average bitrate when mpv knows the total file
    -- size and duration.
    local file_size =
        get_number_property("file-size")

    local duration =
        get_number_property("duration")

    if
        file_size ~= nil and
        file_size > 0 and
        duration ~= nil and
        duration > 0
    then
        return (file_size * 8) / duration
    end

    return nil
end


local function detect_high_bitrate_stream()
    if not current_file_is_network then
        return false
    end

    if current_file_is_live then
        return false
    end

    -- Large caching is reserved for high-memory devices.
    if base_device_profile ~= "device-high" then
        return false
    end

    local bitrate =
        get_stream_bitrate()

    if bitrate == nil then
        return false
    end

    return bitrate >= HIGH_BITRATE_THRESHOLD
end



-- ============================================================
--  BATTERY DETECTION
--
--  If these properties are unavailable in a particular mpvRx
--  build, automatic profile selection continues normally.
-- ============================================================

local function get_battery_level()
    local property_names = {
        "user-data/android/battery-level",
        "user-data/mpvrx/battery-level"
    }

    for _, property_name in ipairs(property_names) do
        local level =
            get_number_property(property_name)

        if level ~= nil then
            return level
        end
    end

    return nil
end


local function device_is_receiving_power()
    local property_names = {
        "user-data/android/battery-charging",
        "user-data/android/battery-plugged",
        "user-data/mpvrx/battery-charging",
        "user-data/mpvrx/battery-plugged"
    }

    local found_property = false

    for _, property_name in ipairs(property_names) do
        local value =
            get_boolean_property(property_name)

        if value ~= nil then
            found_property = true

            if value == true then
                return true
            end
        end
    end

    if found_property then
        return false
    end

    return nil
end


local function update_battery_mode()
    local battery_level =
        get_battery_level()

    if battery_level == nil then
        return false
    end

    local receiving_power =
        device_is_receiving_power()

    -- Do not assume the device is unplugged when charging
    -- information is unavailable.
    if receiving_power == nil then
        return false
    end

    local state_changed = false

    if
        not battery_saver_active and
        battery_level <= LOW_BATTERY_PERCENT and
        not receiving_power
    then
        battery_saver_active = true
        state_changed = true
    end

    if
        battery_saver_active and
        (
            battery_level >= BATTERY_RECOVERY_PERCENT or
            receiving_power
        )
    then
        battery_saver_active = false
        state_changed = true
    end

    if state_changed then
        apply_current_profiles()
    end

    return state_changed
end



-- ============================================================
--  COMPLETED RESULT MESSAGE
-- ============================================================

local function show_completed_result()
    local memory_mb =
        get_total_memory_mb()

    local battery_level =
        get_battery_level()

    local bitrate =
        get_stream_bitrate()

    local lines = {
        "Automatic profile selected",
        "Device: " .. tostring(base_device_profile)
    }

    if memory_mb ~= nil then
        table.insert(
            lines,
            "RAM: " ..
            tostring(memory_mb) ..
            " MiB"
        )
    end

    if battery_saver_active then
        table.insert(
            lines,
            "Battery mode: Saver"
        )
    else
        table.insert(
            lines,
            "Battery mode: Normal"
        )
    end

    if battery_level ~= nil then
        table.insert(
            lines,
            "Battery: " ..
            tostring(math.floor(battery_level)) ..
            "%"
        )
    end

    if current_file_is_live then
        table.insert(
            lines,
            "Source: Live stream"
        )

    elseif current_file_is_network then
        table.insert(
            lines,
            "Source: Online"
        )

    else
        table.insert(
            lines,
            "Source: Local"
        )
    end

    if current_file_is_high_bitrate then
        table.insert(
            lines,
            "Cache: High bitrate"
        )
    else
        table.insert(
            lines,
            "Cache: Standard"
        )
    end

    if bitrate ~= nil then
        table.insert(
            lines,
            string.format(
                "Bitrate: %.1f Mbps",
                bitrate / 1000000
            )
        )
    end

    show_message(
        table.concat(lines, "\n"),
        RESULT_MESSAGE_DURATION
    )
end



-- ============================================================
--  CURRENT FILE ANALYSIS
-- ============================================================

local function analyze_current_file()
    current_file_is_network =
        detect_network_media()

    current_file_is_live =
        detect_live_stream()

    current_file_is_high_bitrate =
        detect_high_bitrate_stream()

    update_battery_mode()
    apply_current_profiles()
    show_completed_result()
end



-- ============================================================
--  FILE EVENTS
-- ============================================================

mp.register_event("file-loaded", function()
    current_file_is_network = false
    current_file_is_live = false
    current_file_is_high_bitrate = false
    active_profile_description = nil

    if analysis_timer ~= nil then
        analysis_timer:kill()
        analysis_timer = nil
    end

    -- Apply the detected device profile immediately.
    apply_current_profiles()

    -- Briefly show that media analysis is underway.
    show_message(
        "Analyzing media...",
        ANALYZING_MESSAGE_DURATION
    )

    analysis_timer = mp.add_timeout(
        FILE_ANALYSIS_DELAY,
        function()
            analysis_timer = nil
            analyze_current_file()
        end
    )
end)


mp.register_event("end-file", function()
    if analysis_timer ~= nil then
        analysis_timer:kill()
        analysis_timer = nil
    end

    current_file_is_network = false
    current_file_is_live = false
    current_file_is_high_bitrate = false
    active_profile_description = nil
end)



-- ============================================================
--  BATTERY PROPERTY OBSERVERS
-- ============================================================

local battery_properties = {
    "user-data/android/battery-level",
    "user-data/android/battery-charging",
    "user-data/android/battery-plugged",
    "user-data/mpvrx/battery-level",
    "user-data/mpvrx/battery-charging",
    "user-data/mpvrx/battery-plugged"
}


for _, property_name in ipairs(battery_properties) do
    mp.observe_property(
        property_name,
        "native",
        function()
            local changed =
                update_battery_mode()

            if
                changed and
                mp.get_property("path", "") ~= ""
            then
                show_completed_result()
            end
        end
    )
end



-- ============================================================
--  OPTIONAL SCRIPT MESSAGES FOR FUTURE BUTTONS
-- ============================================================

-- Show the selected profile again.
--
-- Command:
--
-- script-message auto_profile_status
mp.register_script_message(
    "auto_profile_status",
    function()
        show_completed_result()
    end
)


-- Redetect the device and reanalyse the current media.
--
-- Command:
--
-- script-message auto_profile_refresh
mp.register_script_message(
    "auto_profile_refresh",
    function()
        initialize_device_profile()

        local path =
            mp.get_property("path", "")

        if path ~= nil and path ~= "" then
            show_message(
                "Analyzing media...",
                ANALYZING_MESSAGE_DURATION
            )

            if analysis_timer ~= nil then
                analysis_timer:kill()
            end

            analysis_timer = mp.add_timeout(
                FILE_ANALYSIS_DELAY,
                function()
                    analysis_timer = nil
                    analyze_current_file()
                end
            )
        else
            apply_current_profiles()
        end
    end
)



-- ============================================================
--  INITIALIZATION
-- ============================================================

initialize_device_profile()
update_battery_mode()

log("Automatic profile selector loaded")
