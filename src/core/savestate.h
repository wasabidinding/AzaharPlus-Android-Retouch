// Copyright 2020 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <string>
#include <vector>
#include "common/common_types.h"

namespace Core {

struct SaveStateInfo {
    u32 slot;
    u64 time;
    enum class ValidationStatus {
        OK,
        RevisionDismatch,
    } status;
    std::string build_name;
};

constexpr u32 SaveStateSlotCount = 11; // Maximum count of savestate slots
constexpr u32 AutoSaveSlot = SaveStateSlotCount;

// Pseudo-slot ids for the rotated auto-save history. They never appear as
// regular slots; they only resolve to the .bakN file path beside the auto-save
// .cst. Picked outside the regular [0, SaveStateSlotCount] range to avoid any
// collision with the existing slot numbering.
constexpr u32 AutoSaveBak1Slot = 0xF1;
constexpr u32 AutoSaveBak2Slot = 0xF2;

constexpr bool IsAutoSaveBakSlot(u32 slot) {
    return slot == AutoSaveBak1Slot || slot == AutoSaveBak2Slot;
}

std::vector<SaveStateInfo> ListSaveStates(u64 program_id, u64 movie_id);

// Lists existing rotated auto-save backups (bak1, bak2). Slot field is set to
// AutoSaveBak1Slot / AutoSaveBak2Slot so callers can route loads back through
// the regular signal pipeline.
std::vector<SaveStateInfo> ListAutoSaveBackups(u64 program_id, u64 movie_id);

} // namespace Core
