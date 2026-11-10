// Package accounts finds the account a device token belongs to, and lets new devices join
// it by pairing (ADR 0004).
//
// For now there's one account, whose first device is the token set in config. Every other
// device pairs: it shows a short code, a signed-in device approves it, and it gets a token
// of its own. Account creation comes later and only changes how the first device gets here.
package accounts

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/rdmm404/anti-social/server/internal/apperr"
	"github.com/rdmm404/anti-social/server/internal/text"
)

// Device is a token that may act for the account. Only a hash of the token is kept, so a
// leaked database holds no usable tokens.
type Device struct {
	Name      string
	TokenHash []byte
}

// DeviceInfo is a device as its account sees it. Configured devices come from the server's
// config and can only be removed there.
type DeviceInfo struct {
	ID         int64
	Name       string
	Configured bool
	CreatedAt  time.Time
}

// Pairing is a device waiting to join an account. Only the secret's hash is kept.
type Pairing struct {
	Code       string
	SecretHash []byte
	Name       string
	ExpiresAt  time.Time
}

// ClaimState is where a pairing stands when its device asks.
type ClaimState int

const (
	// Waiting means nobody has approved it yet.
	Waiting ClaimState = iota
	// Claimed means it was approved, and the device now exists with the token it was given.
	Claimed
	// Gone means it expired, was already claimed, or never existed.
	Gone
)

type Store interface {
	// AccountFor returns the account a device belongs to, and false when no device has the hash.
	AccountFor(ctx context.Context, tokenHash []byte) (int64, bool, error)
	// SyncOwner makes sure the first account exists and returns it. Its configured devices
	// become exactly these; paired ones are left alone. It runs as one change, so two servers
	// starting at once agree on the account.
	SyncOwner(ctx context.Context, devices []Device) (int64, error)
	AddDevice(ctx context.Context, accountID int64, device Device) error
	Devices(ctx context.Context, accountID int64) ([]DeviceInfo, error)
	// RemoveDevice removes a device that isn't configured, and returns false if there's none.
	RemoveDevice(ctx context.Context, accountID, id int64) (bool, error)
	// RemoveToken removes the device with this token hash, unless it's configured.
	RemoveToken(ctx context.Context, tokenHash []byte) error

	// AddPairing drops expired pairings, then stores p. It returns ErrCodeTaken when another
	// live pairing has the code, and ErrTooManyPairings when max are already waiting.
	AddPairing(ctx context.Context, p Pairing, now time.Time, max int) error
	// ApprovePairing joins the live, unapproved pairing with this code to the account. It
	// returns the device's name, and false when there's no such pairing.
	ApprovePairing(ctx context.Context, code string, accountID int64, now time.Time) (string, bool, error)
	// ClaimPairing turns an approved, live pairing into a device with tokenHash, in its
	// account, and deletes the pairing. A pairing can be claimed once.
	ClaimPairing(ctx context.Context, secretHash []byte, now time.Time, tokenHash []byte) (ClaimState, error)
}

var (
	ErrUnknownToken    = apperr.New(apperr.Unauthorized, "This device isn't signed in.")
	ErrNoSuchPairing   = apperr.New(apperr.NotFound, "No device is waiting with that code. Codes last 10 minutes, so check the screen for a new one.")
	ErrPairingGone     = apperr.New(apperr.NotFound, "That pairing expired. Start again for a new code.")
	ErrNoSuchDevice    = apperr.New(apperr.NotFound, "That device isn't in this account.")
	ErrTooManyPairings = apperr.New(apperr.Refused, "Too many devices are pairing right now. Try again in a few minutes.")
	// ErrCodeTaken is the store saying two live pairings drew the same code. The service
	// draws another.
	ErrCodeTaken = errors.New("pairing code taken")
)

const (
	PairingLifetime = 10 * time.Minute
	// Pairing needs no sign-in, so this caps what anyone can make the server hold.
	maxPairings = 50
	nameLength  = 60
	// No 0/O or 1/I, so a code read off a TV can't be misread. 8 of 32 symbols is 40 bits,
	// and a code lives 10 minutes behind a signed-in approver.
	codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	codeLength   = 8
)

func Hash(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

// newSecret is 256 random bits as hex, like `openssl rand -hex 32`.
func newSecret() string {
	b := make([]byte, 32)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func newCode() string {
	b := make([]byte, codeLength)
	rand.Read(b)
	for i := range b {
		b[i] = codeAlphabet[int(b[i])%len(codeAlphabet)] // 256 is a multiple of 32: no bias.
	}
	return string(b)
}

// NormalizeCode reads a code as typed: any case, with or without the dash and spaces.
func NormalizeCode(code string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(code) {
		if r != '-' && r != ' ' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// FormatCode shows a code the way the TV does: ABCD-EFGH.
func FormatCode(code string) string {
	if len(code) != codeLength {
		return code
	}
	return code[:4] + "-" + code[4:]
}

func deviceName(name string) string {
	if name = text.Clip(name, nameLength); name == "" {
		return "Device"
	}
	return name
}

type Service struct {
	store Store
	clock func() time.Time
}

func NewService(store Store, clock func() time.Time) *Service {
	if clock == nil {
		clock = time.Now
	}
	return &Service{store, clock}
}

// Authenticate returns the account a device token belongs to, or ErrUnknownToken.
func (s *Service) Authenticate(ctx context.Context, token string) (int64, error) {
	if token == "" {
		return 0, ErrUnknownToken
	}
	id, ok, err := s.store.AccountFor(ctx, Hash(token))
	if err != nil {
		return 0, err
	}
	if !ok {
		return 0, ErrUnknownToken
	}
	return id, nil
}

// EnsureOwner makes sure the server's one account exists and that its configured devices are
// exactly these tokens, keyed by device name. Changing a token signs out the old one; paired
// devices stay. Running it again changes nothing.
func (s *Service) EnsureOwner(ctx context.Context, tokens map[string]string) (int64, error) {
	devices := make([]Device, 0, len(tokens))
	for name, token := range tokens {
		devices = append(devices, Device{Name: name, TokenHash: Hash(token)})
	}
	return s.store.SyncOwner(ctx, devices)
}

// AddDevice gives the account a new device and returns its token, which is never stored.
func (s *Service) AddDevice(ctx context.Context, accountID int64, name string) (string, error) {
	token := newSecret()
	if err := s.store.AddDevice(ctx, accountID, Device{Name: deviceName(name), TokenHash: Hash(token)}); err != nil {
		return "", err
	}
	return token, nil
}

func (s *Service) Devices(ctx context.Context, accountID int64) ([]DeviceInfo, error) {
	return s.store.Devices(ctx, accountID)
}

// RemoveDevice signs a device out for good. Configured devices can only be changed in config.
func (s *Service) RemoveDevice(ctx context.Context, accountID, id int64) error {
	ok, err := s.store.RemoveDevice(ctx, accountID, id)
	if err != nil {
		return err
	}
	if !ok {
		return ErrNoSuchDevice
	}
	return nil
}

// SignOut removes the device holding this token, so signing out also revokes it.
func (s *Service) SignOut(ctx context.Context, token string) error {
	if token == "" {
		return nil
	}
	return s.store.RemoveToken(ctx, Hash(token))
}

// PairingStarted is what a device gets when it asks to pair: the code to show, and the
// secret it keeps to claim its token once the code is approved.
type PairingStarted struct {
	Code      string
	Secret    string
	ExpiresAt time.Time
}

// StartPairing begins pairing a device that has no token. Anyone may ask.
func (s *Service) StartPairing(ctx context.Context, name string) (PairingStarted, error) {
	secret := newSecret()
	expires := s.clock().Add(PairingLifetime)
	for range 5 {
		p := Pairing{Code: newCode(), SecretHash: Hash(secret), Name: deviceName(name), ExpiresAt: expires}
		err := s.store.AddPairing(ctx, p, s.clock(), maxPairings)
		if errors.Is(err, ErrCodeTaken) {
			continue
		}
		if err != nil {
			return PairingStarted{}, err
		}
		return PairingStarted{Code: p.Code, Secret: secret, ExpiresAt: expires}, nil
	}
	return PairingStarted{}, errors.New("no free pairing code after 5 tries")
}

// ApprovePairing lets the device showing this code into the account, and returns its name.
func (s *Service) ApprovePairing(ctx context.Context, accountID int64, code string) (string, error) {
	name, ok, err := s.store.ApprovePairing(ctx, NormalizeCode(code), accountID, s.clock())
	if err != nil {
		return "", err
	}
	if !ok {
		return "", ErrNoSuchPairing
	}
	return name, nil
}

// FinishPairing returns the device's new token once its code is approved, or "" while it
// waits. The token is made here and handed over once; only its hash is kept.
func (s *Service) FinishPairing(ctx context.Context, secret string) (string, error) {
	if secret == "" {
		return "", ErrPairingGone
	}
	token := newSecret()
	state, err := s.store.ClaimPairing(ctx, Hash(secret), s.clock(), Hash(token))
	switch {
	case err != nil:
		return "", err
	case state == Waiting:
		return "", nil
	case state == Gone:
		return "", ErrPairingGone
	}
	return token, nil
}
