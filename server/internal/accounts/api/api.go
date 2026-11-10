// Package api lets requests in by device token.
package api

import (
	"net/http"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/httpapi"
)

// Authenticated runs next with the device's account on the request, or answers 401.
func Authenticated(svc *accounts.Service, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := svc.Authenticate(r.Context(), httpapi.Token(r))
		if err != nil {
			httpapi.Fail(w, r, err)
			return
		}
		next(w, r.WithContext(httpapi.WithAccount(r.Context(), id)))
	}
}
