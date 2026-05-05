// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <chrono>
#include <sstream>
#include <cryptopp/hex.h>
#include <fmt/ranges.h>
#include "common/archives.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "common/scm_rev.h"
#include "common/swap.h"
#include "common/zstd_compression.h"
#include "core/core.h"
#include "core/loader/loader.h"
#include "core/movie.h"
#include "core/savestate.h"
#include "core/savestate_data.h"
#include "network/network.h"

namespace Core {

#pragma pack(push, 1)
struct CSTHeader {
    std::array<u8, 4> filetype;    /// Unique Identifier to check the file type (always "CST"0x1B)
    u64_le program_id;             /// ID of the ROM being executed. Also called title_id
    std::array<u8, 20> revision;   /// Git hash of the revision this savestate was created with
    u64_le time;                   /// The time when this save state was created
    std::array<u8, 20> build_name; /// The build name (Canary/Nightly) with the version number
    u32_le zero = 0;               /// Should be zero, just in case.

    std::array<u8, 192> reserved{}; /// Make heading 256 bytes so it has consistent size
};
static_assert(sizeof(CSTHeader) == 256, "CSTHeader should be 256 bytes");
#pragma pack(pop)

constexpr std::array<u8, 4> header_magic_bytes{{'C', 'S', 'T', 0x1B}};

static std::string GetSaveStatePath(u64 program_id, u64 movie_id, u32 slot) {
    if (IsAutoSaveBakSlot(slot)) {
        const int bak_index = (slot == AutoSaveBak1Slot) ? 1 : 2;
        if (movie_id) {
            return fmt::format("{}{:016X}.movie{:016X}.{:02d}.bak{}.cst",
                               FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id,
                               movie_id, AutoSaveSlot, bak_index);
        }
        return fmt::format("{}{:016X}.{:02d}.bak{}.cst",
                           FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id,
                           AutoSaveSlot, bak_index);
    }
    if (movie_id) {
        return fmt::format("{}{:016X}.movie{:016X}.{:02d}.cst",
                           FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id,
                           movie_id, slot);
    } else {
        return fmt::format("{}{:016X}.{:02d}.cst",
                           FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id, slot);
    }
}

// Rotates the auto-save .cst file into bak1/bak2 before a fresh auto-save
// overwrites it. Failures are logged but never throw — losing one historical
// copy is acceptable; failing the user-visible save is not.
static void RotateAutoSaveBackups(u64 program_id, u64 movie_id) {
    const auto current = GetSaveStatePath(program_id, movie_id, AutoSaveSlot);
    const auto bak1 = GetSaveStatePath(program_id, movie_id, AutoSaveBak1Slot);
    const auto bak2 = GetSaveStatePath(program_id, movie_id, AutoSaveBak2Slot);

    if (FileUtil::Exists(bak2) && !FileUtil::Delete(bak2)) {
        LOG_WARNING(Core, "Failed to delete stale auto-save backup {}", bak2);
    }
    if (FileUtil::Exists(bak1) && !FileUtil::Rename(bak1, bak2)) {
        LOG_WARNING(Core, "Failed to rotate auto-save backup {} -> {}", bak1, bak2);
    }
    if (FileUtil::Exists(current) && !FileUtil::Rename(current, bak1)) {
        LOG_WARNING(Core, "Failed to rotate auto-save {} -> {}", current, bak1);
    }
}

static bool ValidateSaveState(const CSTHeader& header, SaveStateInfo& info, u64 program_id,
                              u64 movie_id) {
    const auto path = GetSaveStatePath(program_id, movie_id, info.slot);
    if (header.filetype != header_magic_bytes) {
        LOG_WARNING(Core, "Invalid save state file {}", path);
        return false;
    }
    info.time = header.time;

    if (header.program_id != program_id) {
        LOG_WARNING(Core, "Save state file isn't for the current game {}", path);
        return false;
    }
    const std::string revision = fmt::format("{:02x}", fmt::join(header.revision, ""));
    const std::string build_name =
        header.zero == 0 ? reinterpret_cast<const char*>(header.build_name.data()) : "";

    if (revision == Common::g_scm_rev) {
        info.status = SaveStateInfo::ValidationStatus::OK;
    } else {
        if (!build_name.empty()) {
            info.build_name = build_name;
        } else if (hash_to_version.find(revision) != hash_to_version.end()) {
            info.build_name = hash_to_version.at(revision);
        }
        if (info.build_name.empty()) {
            LOG_WARNING(Core, "Save state file {} created from a different revision {}", path,
                        revision);
        } else {
            LOG_WARNING(Core,
                        "Save state file {} created from a different build {} with revision {}",
                        path, info.build_name, revision);
        }

        info.status = SaveStateInfo::ValidationStatus::RevisionDismatch;
    }
    return true;
}

static bool ReadSaveStateInfo(u64 program_id, u64 movie_id, SaveStateInfo& info) {
    const auto path = GetSaveStatePath(program_id, movie_id, info.slot);
    if (!FileUtil::Exists(path)) {
        return false;
    }

    FileUtil::IOFile file(path, "rb");
    if (!file) {
        LOG_ERROR(Core, "Could not open file {}", path);
        return false;
    }
    CSTHeader header;
    if (file.GetSize() < sizeof(header)) {
        LOG_ERROR(Core, "File too small {}", path);
        return false;
    }
    if (file.ReadBytes(&header, sizeof(header)) != sizeof(header)) {
        LOG_ERROR(Core, "Could not read from file {}", path);
        return false;
    }
    return ValidateSaveState(header, info, program_id, movie_id);
}

std::vector<SaveStateInfo> ListAutoSaveBackups(u64 program_id, u64 movie_id) {
    std::vector<SaveStateInfo> result;
    result.reserve(2);
    for (u32 slot : {AutoSaveBak1Slot, AutoSaveBak2Slot}) {
        SaveStateInfo info;
        info.slot = slot;
        if (ReadSaveStateInfo(program_id, movie_id, info)) {
            result.emplace_back(std::move(info));
        }
    }
    return result;
}

std::vector<SaveStateInfo> ListSaveStates(u64 program_id, u64 movie_id) {
    std::vector<SaveStateInfo> result;
    result.reserve(SaveStateSlotCount);
    for (u32 slot = 0; slot <= SaveStateSlotCount; ++slot) {
        SaveStateInfo info;
        info.slot = slot;
        if (ReadSaveStateInfo(program_id, movie_id, info)) {
            result.emplace_back(std::move(info));
        }
    }
    return result;
}

void System::SaveState(u32 slot) const {
    if (IsAutoSaveBakSlot(slot)) {
        // bak slots are history-only — they're produced by rotation, never by direct writes.
        throw std::runtime_error("Cannot save to an auto-save backup slot");
    }
    if (app_loader) {
        if (!app_loader->SupportsSaveStates()) {
            throw std::runtime_error("The current app loader doesn't support save states");
        }
    }

    std::ostringstream sstream{std::ios_base::binary};
    // Serialize
    oarchive oa{sstream};
    oa&* this;

    const std::string& str{sstream.str()};
    const auto data = std::span<const u8>{reinterpret_cast<const u8*>(str.data()), str.size()};
    auto buffer = Common::Compression::CompressDataZSTDDefault(data);

    const u64 movie_id = movie.GetCurrentMovieID();
    const auto path = GetSaveStatePath(title_id, movie_id, slot);
    if (!FileUtil::CreateFullPath(path)) {
        throw std::runtime_error("Could not create path " + path);
    }

    if (slot == AutoSaveSlot) {
        RotateAutoSaveBackups(title_id, movie_id);
    }

    FileUtil::IOFile file(path, "wb");
    if (!file) {
        throw std::runtime_error("Could not open file " + path);
    }

    CSTHeader header{};
    header.filetype = header_magic_bytes;
    header.program_id = title_id;
    std::string rev_bytes;
    CryptoPP::StringSource ss(Common::g_scm_rev, true,
                              new CryptoPP::HexDecoder(new CryptoPP::StringSink(rev_bytes)));
    std::memcpy(header.revision.data(), rev_bytes.data(), sizeof(header.revision));
    header.time = std::chrono::duration_cast<std::chrono::seconds>(
                      std::chrono::system_clock::now().time_since_epoch())
                      .count();
    const std::string build_fullname = Common::g_build_fullname;
    std::memset(header.build_name.data(), 0, sizeof(header.build_name));
    std::memcpy(header.build_name.data(), build_fullname.c_str(),
                std::min(build_fullname.length(), sizeof(header.build_name) - 1));

    if (file.WriteBytes(&header, sizeof(header)) != sizeof(header) ||
        file.WriteBytes(buffer.data(), buffer.size()) != buffer.size()) {
        throw std::runtime_error("Could not write to file " + path);
    }
}

void System::LoadState(u32 slot) {
    if (app_loader) {
        if (!app_loader->SupportsSaveStates()) {
            throw std::runtime_error("The current app loader doesn't support save states");
        }
    }
    if (Network::GetRoomMember().lock()->IsConnected()) {
        throw std::runtime_error("Unable to load while connected to multiplayer");
    }

    const u64 movie_id = movie.GetCurrentMovieID();
    const auto path = GetSaveStatePath(title_id, movie_id, slot);

    std::vector<u8> decompressed;
    {
        std::vector<u8> buffer(FileUtil::GetSize(path) - sizeof(CSTHeader));

        FileUtil::IOFile file(path, "rb");

        // load header
        CSTHeader header;
        if (file.ReadBytes(&header, sizeof(header)) != sizeof(header)) {
            throw std::runtime_error("Could not read from file at " + path);
        }

        // validate header
        SaveStateInfo info;
        info.slot = slot;
        if (!ValidateSaveState(header, info, title_id, movie_id)) {
            throw std::runtime_error("Invalid savestate");
        }

        if (file.ReadBytes(buffer.data(), buffer.size()) != buffer.size()) {
            throw std::runtime_error("Could not read from file at " + path);
        }
        decompressed = Common::Compression::DecompressDataZSTD(buffer);
    }
    std::istringstream sstream{
        std::string{reinterpret_cast<char*>(decompressed.data()), decompressed.size()},
        std::ios_base::binary};
    decompressed.clear();

    // Deserialize
    iarchive ia{sstream};
    ia&* this;
}

std::vector<u8> System::SaveStateBuffer() const {
    std::ostringstream sstream{std::ios_base::binary};
    // Serialize
    oarchive oa{sstream};
    oa&* this;

    const std::string& str{sstream.str()};
    const auto data = std::span<const u8>{reinterpret_cast<const u8*>(str.data()), str.size()};
    auto buffer = Common::Compression::CompressDataZSTDDefault(data);

    CSTHeader header{};
    header.filetype = header_magic_bytes;
    header.program_id = title_id;
    std::string rev_bytes;
    CryptoPP::StringSource ss(Common::g_scm_rev, true,
                              new CryptoPP::HexDecoder(new CryptoPP::StringSink(rev_bytes)));
    std::memcpy(header.revision.data(), rev_bytes.data(), sizeof(header.revision));
    header.time = std::chrono::duration_cast<std::chrono::seconds>(
                      std::chrono::system_clock::now().time_since_epoch())
                      .count();
    const std::string build_fullname = Common::g_build_fullname;
    std::memset(header.build_name.data(), 0, sizeof(header.build_name));
    std::memcpy(header.build_name.data(), build_fullname.c_str(),
                std::min(build_fullname.length(), sizeof(header.build_name) - 1));

    std::vector<u8> result((u8*)&header, (u8*)&header + sizeof(header));
    std::copy(buffer.begin(), buffer.end(), std::back_inserter(result));

    return result;
}

bool System::LoadStateBuffer(std::vector<u8> buffer) {
    CSTHeader header;

    if (buffer.size() < sizeof(header)) {
        LOG_ERROR(Core, "Save state too small");
        return false;
    }

    header = *((CSTHeader*)buffer.data());

    if (header.filetype != header_magic_bytes) {
        LOG_ERROR(Core, "Invalid save state");
        return false;
    }

    if (header.program_id != title_id) {
        LOG_ERROR(Core, "Save state isn't for the current game");
        return false;
    }
    std::string revision = fmt::format("{:02x}", fmt::join(header.revision, ""));
    if (revision != Common::g_scm_rev) {
        LOG_ERROR(Core,
                  "Save state file created from a different revision (core: {}, savestate: {})",
                  Common::g_scm_rev, revision);
        return false;
    }

    std::vector<u8> state(buffer.begin() + sizeof(CSTHeader), buffer.end());
    auto decompressed = Common::Compression::DecompressDataZSTD(state);

    std::istringstream sstream{
        std::string{reinterpret_cast<char*>(decompressed.data()), decompressed.size()},
        std::ios_base::binary};
    decompressed.clear();

    // Deserialize
    iarchive ia{sstream};
    ia&* this;

    return true;
}

} // namespace Core
